"""Server-side DNS-01 hook, restricted to the dedicated OMyMoney validation zone."""
import json
import os
from pathlib import Path
import re
import sys
import time

from aliyunsdkcore.client import AcsClient
from aliyunsdkalidns.request.v20150109.AddDomainRecordRequest import AddDomainRecordRequest
from aliyunsdkalidns.request.v20150109.DeleteDomainRecordRequest import DeleteDomainRecordRequest

DOMAIN = "omymoney.origenclub.cn"
ZONE = "omymoney-acme.origenclub.cn"


def main():
    if os.environ.get("CERTBOT_DOMAIN") != DOMAIN:
        raise ValueError("Unexpected certificate hostname")
    credentials = json.loads(Path("/data/omymoney/acme/dns-credentials.json").read_text())
    client = AcsClient(credentials["accessKeyId"], credentials["accessKeySecret"], "cn-hangzhou",
                       timeout=20, auto_retry=False)
    mode = sys.argv[1]
    if mode == "auth":
        value = os.environ["CERTBOT_VALIDATION"]
        if not re.fullmatch(r"[A-Za-z0-9_-]{20,200}", value):
            raise ValueError("Invalid challenge")
        request = AddDomainRecordRequest()
        request.set_DomainName(ZONE)
        request.set_RR("@")
        request.set_Type("TXT")
        request.set_Value(value)
        request.set_TTL(600)
    elif mode == "cleanup":
        record = os.environ.get("CERTBOT_AUTH_OUTPUT", "").strip()
        if not re.fullmatch(r"[0-9]{1,40}", record):
            return
        request = DeleteDomainRecordRequest()
        request.set_RecordId(record)
    else:
        raise ValueError("Unknown hook")
    request.set_protocol_type("https")
    response = json.loads(client.do_action_with_exception(request))
    if mode == "auth":
        print(response["RecordId"], flush=True)
        # Renewals are rare; allow public recursive caches time to observe the new TXT.
        time.sleep(120)


if __name__ == "__main__":
    main()
