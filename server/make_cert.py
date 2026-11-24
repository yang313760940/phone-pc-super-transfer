"""生成自签证书，覆盖局域网 IP（可附加主机名），供手机 HTTPS 访问使用。

用法：
  python server/make_cert.py                 # 自动取本机局域网 IPv4
  python server/make_cert.py 192.168.31.61   # 指定 IP（可多个）
产物：server/certs/cert.pem、server/certs/key.pem（含 SAN=DNS/IP）。

注意：这是自签证书，手机首次访问需手动接受例外/信任；证书 SAN 必含手机实际访问的 IP，
否则即使信任也仍会报名称不匹配。
"""

from __future__ import annotations

import datetime
import ipaddress
import socket
import sys
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

CERT_DIR = Path(__file__).resolve().parent / "certs"


def local_ips() -> list[str]:
    ips = set()
    try:
        host = socket.gethostname()
        for info in socket.getaddrinfo(host, None):
            addr = info[4][0]
            try:
                ip = ipaddress.ip_address(addr)
            except ValueError:
                continue
            if ip.version == 4 and not ip.is_loopback and not ip.is_link_local:
                ips.add(str(ip))
    except OSError:
        pass
    return sorted(ips)


def build(ips: list[str], hostnames: list[str]) -> None:
    CERT_DIR.mkdir(parents=True, exist_ok=True)
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)

    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, ips[0] if ips else "localhost"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "PhoneVoice MVP (self-signed)"),
    ])

    sans = [x509.DNSName("localhost")]
    for h in hostnames:
        sans.append(x509.DNSName(h))
    sans.append(x509.IPAddress(ipaddress.ip_address("127.0.0.1")))
    for addr in ips:
        try:
            sans.append(x509.IPAddress(ipaddress.ip_address(addr)))
        except ValueError:
            sans.append(x509.DNSName(addr))

    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=825))
        .add_extension(x509.SubjectAlternativeName(sans), critical=False)
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )

    (CERT_DIR / "key.pem").write_bytes(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    (CERT_DIR / "cert.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))

    print("证书已生成：", CERT_DIR)
    print("  cert.pem / key.pem")
    print("  SAN IP:", ", ".join(ips), "+ 127.0.0.1")
    print("  SAN DNS: localhost", ("+ " + ", ".join(hostnames)) if hostnames else "")


if __name__ == "__main__":
    given = [a for a in sys.argv[1:] if a]
    ips = given or local_ips()
    if not ips:
        print("未自动取到局域网 IP，请显式传入，例如：python make_cert.py 192.168.31.61")
        raise SystemExit(1)
    build(ips, [socket.gethostname()])
