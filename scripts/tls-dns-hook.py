#!/usr/bin/env python3
"""Local Certbot DNS-01 hook. Uses the existing Aliyun CLI profile, never exports its credentials."""
import json
import os
import re
import subprocess
import sys
import time

DOMAIN = "omymoney.origenclub.cn"
ZONE = "omymoney-acme.origenclub.cn"
RR = "@"
CLI = "/opt/homebrew/bin/aliyun"


def api(action, **params):
    command = [CLI, "alidns", action]
    for key, value in params.items():
        command += [f"--{key}", str(value)]
    response = subprocess.run(command, capture_output=True, text=True, check=True, timeout=30)
    return json.loads(response.stdout)


def main():
    mode = sys.argv[1]
    if mode == "create-cname":
        records = api("DescribeDomainRecords", DomainName="origenclub.cn", RRKeyWord="omymoney", PageSize=100)
        exact = [row for row in records["DomainRecords"]["Record"] if row["RR"] == "omymoney"]
        if exact:
            if len(exact) != 1 or exact[0]["Type"] != "CNAME" or exact[0]["Value"].rstrip(".") != "ao.rainplay.cn":
                raise RuntimeError("Existing record differs; refusing to replace it")
        else:
            api("AddDomainRecord", DomainName="origenclub.cn", RR="omymoney", Type="CNAME", Value="ao.rainplay.cn", TTL=600)
        print("OMyMoney CNAME is configured")
        return
    if os.environ.get("CERTBOT_DOMAIN") != DOMAIN:
        raise RuntimeError("Hook restricted to the OMyMoney hostname")
    if mode == "cleanup":
        record = os.environ.get("CERTBOT_AUTH_OUTPUT", "").strip()
        if re.fullmatch(r"[0-9]{1,40}", record):
            api("DeleteDomainRecord", RecordId=record)
        return
    if mode != "auth":
        raise ValueError("mode")
    validation = os.environ["CERTBOT_VALIDATION"]
    if not re.fullmatch(r"[A-Za-z0-9_-]{20,200}", validation):
        raise ValueError("validation")
    record = api("AddDomainRecord", DomainName=ZONE, RR=RR, Type="TXT", Value=validation, TTL=600)["RecordId"]
    try:
        nameservers = subprocess.run(["/usr/bin/dig", "+short", "NS", ZONE], capture_output=True,
                                     text=True, check=True, timeout=10).stdout.split()
        if not nameservers:
            raise RuntimeError("Cannot resolve authoritative nameservers")
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            propagated = True
            for nameserver in nameservers:
                result = subprocess.run(["/usr/bin/dig", "+short", "+time=3", "+tries=1", f"@{nameserver}",
                                         "TXT", ZONE], capture_output=True, text=True, timeout=5)
                propagated &= f'"{validation}"' in result.stdout.splitlines()
            if propagated:
                print(record)
                return
            time.sleep(5)
        raise RuntimeError("DNS challenge propagation timed out")
    except BaseException:
        api("DeleteDomainRecord", RecordId=record)
        raise


if __name__ == "__main__":
    main()
