"""Minimal Minecraft 1.8 (protocol 47) client probe.

Connects to the proxy, performs handshake + login start with a fixed name,
waits for login success, then lingers so the proxy can route the connection.
Usage: python3 probe_client.py <linger_seconds>
"""
import socket
import struct
import sys
import time

NAME = "routerprobe"


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("eof")
        buf += chunk
    return buf


def recv_varint(sock):
    val = 0
    shift = 0
    while True:
        b = recv_exact(sock, 1)[0]
        val |= (b & 0x7F) << shift
        if not (b & 0x80):
            return val
        shift += 7
        if shift > 35:
            raise ValueError("varint too long")


def varint_bytes(val):
    out = b""
    while True:
        b = val & 0x7F
        val >>= 7
        if val:
            out += bytes([b | 0x80])
        else:
            out += bytes([b])
            return out


def pack_string(s):
    b = s.encode("utf-8")
    return varint_bytes(len(b)) + b


def read_string(payload, offset):
    length = 0
    shift = 0
    while True:
        b = payload[offset]
        offset += 1
        length |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return payload[offset:offset + length].decode("utf-8"), offset + length


def main():
    linger = int(sys.argv[1]) if len(sys.argv) > 1 else 12
    sock = socket.create_connection(("127.0.0.1", 25565), timeout=15)

    handshake = bytes([0x00]) + varint_bytes(47) + pack_string("localhost") \
        + struct.pack(">H", 25565) + varint_bytes(2)
    sock.sendall(varint_bytes(len(handshake)) + handshake)

    login_start = bytes([0x00]) + pack_string(NAME)
    sock.sendall(varint_bytes(len(login_start)) + login_start)

    while True:
        ln = recv_varint(sock)
        payload = recv_exact(sock, ln)
        pid = payload[0]
        if pid == 0x02:
            uuid, off = read_string(payload, 1)
            name, _ = read_string(payload, off)
            print("PROBE LOGIN SUCCESS uuid=%s name=%s" % (uuid, name), flush=True)
            break
        if pid == 0x00:
            reason, _ = read_string(payload, 1)
            print("PROBE DISCONNECTED reason=%s" % reason, flush=True)
            return
        print("PROBE unexpected packet id=%d" % pid, flush=True)

    time.sleep(linger)
    sock.close()
    print("PROBE done (lingered %ds)" % linger, flush=True)


if __name__ == "__main__":
    main()
