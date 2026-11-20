from __future__ import annotations

import importlib.util
import socket
import struct
import threading
from pathlib import Path

MODULE = Path(__file__).with_name("vnc_rfb.py")
SPEC = importlib.util.spec_from_file_location("vnc_rfb", MODULE)
rfb = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(rfb)


def _read_exact(sock: socket.socket, size: int) -> bytes:
    out = bytearray()
    while len(out) < size:
        chunk = sock.recv(size - len(out))
        if not chunk:
            raise AssertionError("client closed the RFB socket early")
        out.extend(chunk)
    return bytes(out)


def test_des_matches_published_known_answer_vector():
    key = bytes.fromhex("133457799BBCDFF1")
    plaintext = bytes.fromhex("0123456789ABCDEF")
    assert rfb.des_encrypt_block(plaintext, key).hex().upper() == "85E813540F0AB405"


def test_vnc_password_uses_bit_reversed_first_eight_ascii_characters():
    assert rfb.vnc_password_key("password-and-extra").hex() == "0e86ceceeef64e26"
    assert rfb.vnc_password_key("12345678-extra") == rfb.vnc_password_key("12345678")
    try:
        rfb.vnc_password_key("short")
    except ValueError:
        pass
    else:
        raise AssertionError("short VNC passwords must be rejected")


def _round_trip(server_minor: int, accepted: bool = True):
    client, server = socket.socketpair()
    password = "A7x!9Qm#safe-extra"
    challenge = bytes.fromhex("00112233445566778899aabbccddeeff")
    server_error = []

    def server_thread():
        try:
            server.sendall(f"RFB 003.{server_minor:03d}\n".encode("ascii"))
            response_version = _read_exact(server, 12)
            assert response_version == (b"RFB 003.008\n" if server_minor >= 8 else f"RFB 003.{server_minor:03d}\n".encode("ascii"))
            if server_minor >= 7:
                server.sendall(b"\x01\x02")
                assert _read_exact(server, 1) == b"\x02"
            else:
                server.sendall(struct.pack(">I", 2))
            server.sendall(challenge)
            response = _read_exact(server, 16)
            matches = response == rfb.vnc_response(challenge, password)
            server.sendall(struct.pack(">I", 0 if matches and accepted else 1))
        except Exception as exc:
            server_error.append(exc)
        finally:
            server.close()

    thread = threading.Thread(target=server_thread, daemon=True)
    thread.start()
    try:
        try:
            rfb.authenticate_socket(client, password)
            outcome = True
        except rfb.RfbAuthenticationError:
            outcome = False
    finally:
        client.close()
        thread.join(timeout=2)
    assert not thread.is_alive()
    assert not server_error, server_error
    assert outcome is accepted


def test_rfb_38_vnc_authentication_succeeds():
    _round_trip(8, accepted=True)


def test_rfb_37_vnc_authentication_succeeds():
    _round_trip(7, accepted=True)


def test_rfb_33_vnc_authentication_succeeds():
    _round_trip(3, accepted=True)


def test_rfb_rejected_authentication_fails_closed():
    _round_trip(8, accepted=False)


def test_non_vnc_security_type_is_rejected():
    client, server = socket.socketpair()
    def server_thread():
        try:
            server.sendall(b"RFB 003.008\n")
            _read_exact(server, 12)
            server.sendall(b"\x01\x01")  # None authentication only
            # Server closes after advertising an unsupported security type.
        finally:
            server.close()
    thread = threading.Thread(target=server_thread, daemon=True)
    thread.start()
    try:
        try:
            rfb.authenticate_socket(client, "A7x!9Qm#safe-extra")
        except rfb.RfbAuthenticationError as exc:
            assert "did not offer VNC Authentication" in str(exc)
        else:
            raise AssertionError("unauthenticated security type must not be accepted")
    finally:
        client.close()
        thread.join(timeout=2)
    assert not thread.is_alive()
