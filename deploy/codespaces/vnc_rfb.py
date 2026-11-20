#!/usr/bin/env python3
"""Minimal RFB VNC Authentication client used by the local Codespaces self-check.

Only the standard VNC Authentication security type (2) is supported. This module
implements the DES challenge response required by RFB; it does not expose a VNC
listener or accept any inbound client connections.
"""
from __future__ import annotations

import socket
import struct
from typing import Sequence

# FIPS 46-3 DES permutation tables. Positions are one-based from the MSB.
_IP = (58,50,42,34,26,18,10,2,60,52,44,36,28,20,12,4,62,54,46,38,30,22,14,6,64,56,48,40,32,24,16,8,57,49,41,33,25,17,9,1,59,51,43,35,27,19,11,3,61,53,45,37,29,21,13,5,63,55,47,39,31,23,15,7)
_FP = (40,8,48,16,56,24,64,32,39,7,47,15,55,23,63,31,38,6,46,14,54,22,62,30,37,5,45,13,53,21,61,29,36,4,44,12,52,20,60,28,35,3,43,11,51,19,59,27,34,2,42,10,50,18,58,26,33,1,41,9,49,17,57,25)
_E = (32,1,2,3,4,5,4,5,6,7,8,9,8,9,10,11,12,13,12,13,14,15,16,17,16,17,18,19,20,21,20,21,22,23,24,25,24,25,26,27,28,29,28,29,30,31,32,1)
_P = (16,7,20,21,29,12,28,17,1,15,23,26,5,18,31,10,2,8,24,14,32,27,3,9,19,13,30,6,22,11,4,25)
_PC1 = (57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35,27,19,11,3,60,52,44,36,63,55,47,39,31,23,15,7,62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,28,20,12,4)
_PC2 = (14,17,11,24,1,5,3,28,15,6,21,10,23,19,12,4,26,8,16,7,27,20,13,2,41,52,31,37,47,55,30,40,51,45,33,48,44,49,39,56,34,53,46,42,50,36,29,32)
_SHIFTS = (1,1,2,2,2,2,2,2,1,2,2,2,2,2,2,1)
_SBOX = (
    ((14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7),(0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8),(4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0),(15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13)),
    ((15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10),(3,13,4,7,15,2,8,14,12,0,1,10,6,9,11,5),(0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15),(13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9)),
    ((10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8),(13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1),(13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7),(1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12)),
    ((7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15),(13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9),(10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4),(3,15,0,6,10,1,13,8,9,4,5,11,12,7,2,14)),
    ((2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9),(14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6),(4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14),(11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3)),
    ((12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11),(10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8),(9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6),(4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13)),
    ((4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1),(13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6),(1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2),(6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12)),
    ((13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7),(1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2),(7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8),(2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11)),
)


class RfbAuthenticationError(RuntimeError):
    pass


def _permute(value: int, table: Sequence[int], width: int) -> int:
    result = 0
    for position in table:
        result = (result << 1) | ((value >> (width - position)) & 1)
    return result


def _subkeys(key: bytes) -> list[int]:
    if len(key) != 8:
        raise ValueError("DES key must contain exactly eight bytes")
    selected = _permute(int.from_bytes(key, "big"), _PC1, 64)
    left, right = selected >> 28, selected & ((1 << 28) - 1)
    keys: list[int] = []
    for shift in _SHIFTS:
        left = ((left << shift) | (left >> (28 - shift))) & ((1 << 28) - 1)
        right = ((right << shift) | (right >> (28 - shift))) & ((1 << 28) - 1)
        keys.append(_permute((left << 28) | right, _PC2, 56))
    return keys


def des_encrypt_block(block: bytes, key: bytes) -> bytes:
    if len(block) != 8:
        raise ValueError("DES block must contain exactly eight bytes")
    keys = _subkeys(key)
    state = _permute(int.from_bytes(block, "big"), _IP, 64)
    left, right = state >> 32, state & 0xFFFFFFFF
    for subkey in keys:
        expanded = _permute(right, _E, 32) ^ subkey
        substituted = 0
        for index, box in enumerate(_SBOX):
            six = (expanded >> (42 - 6 * index)) & 0x3F
            row = ((six & 0x20) >> 4) | (six & 1)
            column = (six >> 1) & 0x0F
            substituted = (substituted << 4) | box[row][column]
        left, right = right, left ^ _permute(substituted, _P, 32)
    return _permute((right << 32) | left, _FP, 64).to_bytes(8, "big")


def _reverse_bits(value: int) -> int:
    result = 0
    for _ in range(8):
        result = (result << 1) | (value & 1)
        value >>= 1
    return result


def vnc_password_key(password: str) -> bytes:
    raw = password.encode("ascii")
    if len(raw) < 8 or any(value < 0x21 or value > 0x7E for value in raw):
        raise ValueError("VNC password must contain at least eight printable ASCII characters")
    return bytes(_reverse_bits(value) for value in raw[:8])


def vnc_response(challenge: bytes, password: str) -> bytes:
    if len(challenge) != 16:
        raise ValueError("RFB VNC challenge must be exactly 16 bytes")
    key = vnc_password_key(password)
    return b"".join(des_encrypt_block(challenge[i:i + 8], key) for i in (0, 8))


def _read_exact(sock: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        block = sock.recv(size - len(chunks))
        if not block:
            raise RfbAuthenticationError("VNC server closed during authentication")
        chunks.extend(block)
    return bytes(chunks)


def authenticate_socket(sock: socket.socket, password: str) -> None:
    """Complete the RFB VNC Authentication exchange on an already-connected socket."""
    banner = _read_exact(sock, 12)
    if not banner.startswith(b"RFB 003.") or not banner.endswith(b"\n"):
        raise RfbAuthenticationError("unsupported RFB server banner")
    try:
        minor = int(banner[8:11])
    except ValueError as exc:
        raise RfbAuthenticationError("invalid RFB server version") from exc
    if minor >= 7:
        sock.sendall(b"RFB 003.008\n" if minor >= 8 else b"RFB 003.007\n")
        count = _read_exact(sock, 1)[0]
        if count == 0:
            raise RfbAuthenticationError("VNC server offered no security types")
        security_types = _read_exact(sock, count)
        if 2 not in security_types:
            raise RfbAuthenticationError("VNC server did not offer VNC Authentication")
        sock.sendall(b"\x02")
    elif minor == 3:
        sock.sendall(b"RFB 003.003\n")
        security_type = struct.unpack(">I", _read_exact(sock, 4))[0]
        if security_type != 2:
            raise RfbAuthenticationError("VNC server did not select VNC Authentication")
    else:
        raise RfbAuthenticationError("unsupported RFB protocol version")
    challenge = _read_exact(sock, 16)
    sock.sendall(vnc_response(challenge, password))
    result = struct.unpack(">I", _read_exact(sock, 4))[0]
    if result != 0:
        raise RfbAuthenticationError("VNC server rejected the configured password")


def authenticate_localhost(port: int, password: str, timeout: float = 3.0) -> None:
    if not 1 <= port <= 65535 or not 0.1 <= timeout <= 10.0:
        raise ValueError("invalid RFB probe connection settings")
    with socket.create_connection(("127.0.0.1", port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        authenticate_socket(sock, password)
