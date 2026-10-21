package app.omymoney.data.exchange

import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.util.zip.CRC32
import java.util.Locale
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import com.fasterxml.aalto.stax.InputFactoryImpl
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.CellType
import app.omymoney.data.LedgerJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable enum class ExchangeFormat { CSV, XLSX, JSON }
@Serializable data class TableRow(val number: Int, val cells: List<String>, val error: String? = null)
@Serializable data class ExchangeTable(val columns: List<String>, val rows: List<TableRow>)
class ExchangeException(val code: String, cause: Throwable? = null) : Exception(code, cause)

internal object TableCodec {
    const val MAX_CELL = 32_768
    const val MAX_ROWS = 10_000
    const val MAX_COLUMNS = 64
    const val MAX_BYTES = 16 * 1024 * 1024
    fun read(input: InputStream, format: ExchangeFormat): ExchangeTable = try {
        val bytes = boundedBytes(input, MAX_BYTES)
        when (format) {
            ExchangeFormat.CSV -> readCsv(utf8(bytes).removePrefix("\uFEFF"))
            ExchangeFormat.XLSX -> readXlsx(bytes)
            ExchangeFormat.JSON -> LedgerJson.format.decodeFromString<ExchangeTable>(utf8(bytes)).let { table ->
                table.copy(rows = table.rows.map { row(it.number, it.cells, table.columns.size, it.error) }).also(::validate)
            }
        }
    } catch (error: ExchangeException) { throw error }
    catch (error: Exception) { throw ExchangeException("invalid_${format.name.lowercase()}", error) }

    fun write(table: ExchangeTable, format: ExchangeFormat, output: OutputStream) {
        validate(table)
        if (table.rows.any { it.error != null }) throw ExchangeException("invalid_row")
        when (format) {
            ExchangeFormat.CSV -> {
                val writer = output.writer(Charsets.UTF_8)
                writer.write("\uFEFF")
                val printer = CSVPrinter(writer, CSVFormat.RFC4180)
                printer.printRecord(table.columns.map(::csvSafe))
                table.rows.forEach { printer.printRecord(it.cells.map(::csvSafe)) }
                printer.flush()
            }
            ExchangeFormat.XLSX -> Workbook(output, "OMyMoney", "1.0").use { workbook ->
                val sheet = workbook.newWorksheet("Bills")
                // All cells are strings: neither precision rounding nor formula execution is allowed.
                (listOf(table.columns) + table.rows.map { it.cells }).forEachIndexed { row, cells ->
                    cells.forEachIndexed { column, text -> sheet.inlineString(row, column, text) }
                }
                sheet.freezePane(0, 1)
            }
            ExchangeFormat.JSON -> output.write(LedgerJson.format.encodeToString(table).toByteArray())
        }
        output.flush()
    }

    private fun readCsv(text: String): ExchangeTable = CSVFormat.RFC4180.parse(text.reader()).use { parser ->
        val rows = mutableListOf<TableRow>()
        val iterator = parser.iterator()
        val columns = if (iterator.hasNext()) iterator.next().toList() else emptyList()
        while (iterator.hasNext()) {
            if (rows.size >= MAX_ROWS) throw ExchangeException("row_limit")
            val record = iterator.next()
            val raw = record.toList()
            val own = columns.firstOrNull() == "format" && raw.firstOrNull() == "OMyMoney-1"
            val cells = if (own) raw.map { if (it.startsWith("'") && csvNeedsEscape(it.drop(1))) it.drop(1) else it } else raw
            rows += row(record.recordNumber.toInt(), cells, columns.size)
        }
        ExchangeTable(columns, rows).also(::validate)
    }

    private fun readXlsx(bytes: ByteArray): ExchangeTable {
        preflightXlsx(bytes)
        return ReadableWorkbook(bytes.inputStream()).use { workbook ->
            if (workbook.sheets.use { it.count() } != 1L) throw ExchangeException("multiple_sheets")
            workbook.firstSheet.openStream().use { stream ->
                val iterator = stream.iterator()
                val header = if (iterator.hasNext()) iterator.next() else throw ExchangeException("missing_header")
                if (header.rowNum != 1 || header.any { it?.type == CellType.FORMULA }) throw ExchangeException("invalid_header")
                val columns = (0 until header.cellCount).map { header.getCellText(it) }
                val rows = mutableListOf<TableRow>()
                while (iterator.hasNext()) {
                    if (rows.size >= MAX_ROWS) throw ExchangeException("row_limit")
                    val next = iterator.next()
                    val cells = (0 until maxOf(columns.size, next.cellCount)).map { next.getCellText(it) }
                    val error = if (next.any { it?.type == CellType.FORMULA }) "formula_cell"
                        else if (next.any { it?.type == CellType.ERROR }) "error_cell" else null
                    rows += row(next.rowNum, cells, columns.size, error)
                }
                ExchangeTable(columns, rows).also(::validate)
            }
        }
    }

    private fun row(number: Int, cells: List<String>, columns: Int, error: String? = null) = TableRow(number, cells,
        error ?: when { cells.size != columns -> "column_count"; cells.any { it.length > MAX_CELL } -> "cell_limit"; else -> null })

    private fun validate(table: ExchangeTable) {
        if (table.columns.size !in 1..MAX_COLUMNS || table.columns.any { it.isBlank() || it.length > 256 } ||
            table.columns.distinct().size != table.columns.size) throw ExchangeException("invalid_header")
        if (table.rows.size > MAX_ROWS) throw ExchangeException("row_limit")
        if (table.rows.map { it.number }.distinct().size != table.rows.size || table.rows.any { it.number !in 2..MAX_ROWS + 1 }) {
            throw ExchangeException("invalid_row")
        }
        if (table.rows.any { it.cells.size > MAX_COLUMNS }) throw ExchangeException("column_limit")
    }

    private fun csvNeedsEscape(text: String) = text.startsWith("'") || text.trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
        text.firstOrNull() in listOf('\t', '\r', '\n')
    private fun csvSafe(text: String) = if (csvNeedsEscape(text)) "'$text" else text
    private fun utf8(bytes: ByteArray) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

    internal fun boundedBytes(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > limit - out.size()) throw ExchangeException("file_limit")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun preflightXlsx(bytes: ByteArray) {
        val factory = InputFactoryImpl().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        var remaining = 32 * 1024 * 1024
        val contents = linkedMapOf<String, ByteArray>()
        // Inspect the same central-directory entries and bytes as fastexcel's OPC reader.
        ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(bytes)).get().use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name.lowercase(Locale.ROOT)
                if (name in contents || contents.size >= 256 || name.startsWith("/") || ".." in name.split('/') || !zip.canReadEntryData(entry)) throw ExchangeException("invalid_xlsx")
                val content = zip.getInputStream(entry).use { boundedBytes(it, remaining) }
                if (CRC32().apply { update(content) }.value != entry.crc) throw ExchangeException("invalid_xlsx")
                remaining -= content.size
                contents[name] = content
            }
        }
        if ("[content_types].xml" !in contents) throw ExchangeException("invalid_xlsx")
        val xmlParts = contents.keys.filter { it.endsWith(".xml") || it.endsWith(".rels") }.toMutableSet()
        val checked = mutableSetOf<String>()
        var sharedCount: Int? = null
        val references = mutableListOf<Int>()
        while (checked.size < xmlParts.size) {
            val name = xmlParts.first { it !in checked }
            val content = contents[name] ?: throw ExchangeException("invalid_xlsx")
            val xml = factory.createXMLStreamReader(content.inputStream())
            try {
                var depth = 0; var root = ""; var strings = 0; var sharedCell = false; var sharedValue: StringBuilder? = null
                while (xml.hasNext()) when (xml.next()) {
                    XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> throw ExchangeException("unsafe_xml")
                    XMLStreamConstants.START_ELEMENT -> {
                        if (++depth > 64 || xml.attributeCount > 128) throw ExchangeException("xml_limit")
                        if (depth == 1) root = xml.localName
                        if (root == "Types" && xml.localName == "Override") {
                            val type = xml.getAttributeValue(null, "ContentType").orEmpty()
                            if (type.endsWith("+xml") || type == "application/xml") {
                                val part = xml.getAttributeValue(null, "PartName")?.removePrefix("/")?.lowercase(Locale.ROOT)
                                    ?: throw ExchangeException("invalid_xlsx")
                                xmlParts += part
                            }
                        }
                        if (xml.localName == "Relationship" && xml.getAttributeValue(null, "TargetMode") == "External") throw ExchangeException("external_link")
                        if (root == "sst" && xml.localName == "si" && ++strings > (MAX_ROWS + 1) * MAX_COLUMNS) throw ExchangeException("shared_string_limit")
                        if (root == "worksheet") {
                            if (xml.localName == "row") {
                                val number = xml.getAttributeValue(null, "r")
                                if (number != null && (number.toIntOrNull() ?: 0) !in 1..MAX_ROWS + 1) throw ExchangeException("row_limit")
                            }
                            if (xml.localName == "c") {
                                val ref = xml.getAttributeValue(null, "r") ?: throw ExchangeException("invalid_cell")
                                val match = Regex("([A-Z]{1,3})([0-9]{1,8})").matchEntire(ref) ?: throw ExchangeException("invalid_cell")
                                val column = match.groupValues[1].fold(0) { value, letter -> value * 26 + letter.code - 'A'.code + 1 }
                                if (column > MAX_COLUMNS || match.groupValues[2].toInt() !in 1..MAX_ROWS + 1) throw ExchangeException("cell_limit")
                                sharedCell = xml.getAttributeValue(null, "t") == "s"
                            }
                            if (xml.localName == "v" && sharedCell) sharedValue = StringBuilder()
                        }
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> sharedValue?.let {
                        if (it.length + xml.textLength > 12) throw ExchangeException("invalid_shared_string")
                        it.append(xml.text)
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        if (root == "worksheet" && xml.localName == "v" && sharedValue != null) {
                            references += sharedValue.toString().toIntOrNull() ?: throw ExchangeException("invalid_shared_string")
                            sharedValue = null
                        }
                        if (root == "worksheet" && xml.localName == "c") sharedCell = false
                        depth--
                    }
                }
                if (root == "sst") {
                    if (sharedCount != null) throw ExchangeException("invalid_shared_string")
                    sharedCount = strings
                }
            } finally { xml.close() }
            checked += name
        }
        if (references.any { it < 0 || it >= (sharedCount ?: 0) }) throw ExchangeException("invalid_shared_string")
    }
}
