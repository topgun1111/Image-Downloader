"""Create the temporary apksigner inputs from the repository's permanent RSA key."""
from datetime import datetime, timezone
import hashlib
import os
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.x509.oid import NameOID

out = Path(os.environ["RUNNER_TEMP"])
key = serialization.load_pem_private_key(os.environ["ANDROID_RELEASE_KEY_PEM"].encode(), None)
der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
owner = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Image Downloader Release")])
# Fixed identity and validity make the certificate identical on every runner.
cert = (x509.CertificateBuilder().subject_name(owner).issuer_name(owner)
        .public_key(key.public_key()).serial_number(int.from_bytes(hashlib.sha256(der).digest()[:19], "big") or 1)
        .not_valid_before(datetime(2026, 1, 1, tzinfo=timezone.utc))
        .not_valid_after(datetime(2099, 1, 1, tzinfo=timezone.utc)).sign(key, hashes.SHA256()))
os.umask(0o077)
(out/"signer.pk8").write_bytes(key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
(out/"signer.der").write_bytes(cert.public_bytes(serialization.Encoding.DER))
