#!/usr/bin/env python3
"""Генератор синтетических DJI FlightRecord (.txt) для тестов.

Пишет два файла:
  * v12 — записи с XOR-обфускацией (ключ из CRC64), без внешних ключей;
  * v14 — XOR + AES-256-CBC, ключи которого обычно выдаёт DJI по API.
    Для теста ключи известны и записываются в *.keychains.json.

Структура повторяет описание из dji-log-parser (MIT, Luc Vauvillier).
Файлы проверяются эталонным парсером dji-log-parser-js (tools/verify_dji.mjs).

    pip install pycryptodome
    python3 tools/make_dji_samples.py
"""
import base64
import json
import math
import os
import struct
import sys

from Crypto.Cipher import AES
from Crypto.Util.Padding import pad

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_RES = os.path.join(ROOT, "core", "src", "test", "resources")

# CRC-64/Jones (как в Redis и crate crc64), отражённый, без финального XOR.
POLY = 0x95AC9329AC4BC9B5
TABLE = []
for i in range(256):
    c = i
    for _ in range(8):
        c = (c >> 1) ^ POLY if c & 1 else c >> 1
    TABLE.append(c)


def crc64(crc, data):
    for b in data:
        crc = TABLE[(crc ^ b) & 0xFF] ^ (crc >> 8)
    return crc


def xor_key(seed, record_type):
    magic = (0x123456789ABCDEF0 * seed) & 0xFFFFFFFFFFFFFFFF
    return struct.pack("<Q", crc64((seed + record_type) & 0xFF, struct.pack("<Q", magic)))


def xor(data, key):
    return bytes(b ^ key[i % 8] for i, b in enumerate(data))


FEATURE_BASE = 1
FEATURE_CUSTOM = 7
FEATURE_BATTERY = 13
FEATURE_NAMES = {1: "FR_Standardization_Feature_Base_1", 7: "FR_Standardization_Feature_DJIFlyCustom_7",
                 13: "FR_Standardization_Feature_Battery_13"}


def feature_v14(record_type):
    return {1: 1, 2: 1, 5: 7, 7: 13, 9: 7, 10: 7, 24: 7}.get(record_type)


class Writer:
    def __init__(self, version, keys=None, chain="feature"):
        self.version = version
        self.keys = keys  # feature -> [key, iv] (iv обновляется по цепочке)
        self.chain = chain  # "feature" (как в эталоне) или "type" — цепочка IV по типу записи
        self.type_iv = {}
        self.out = bytearray()
        self.seed = 17

    def record(self, rtype, content):
        self.seed = (self.seed * 73 + 41) & 0xFF
        seed = self.seed
        feature = feature_v14(rtype) if self.version >= 13 else None
        if self.version >= 13 and feature is not None and self.keys is not None:
            key, iv = self.keys[feature]
            if self.chain == "type":
                iv = self.type_iv.get(rtype, self.initial_iv[feature])
            ct = AES.new(key, AES.MODE_CBC, iv).encrypt(pad(content, 16))
            self.keys[feature][1] = ct[-16:]  # следующий IV — последний блок
            self.type_iv[rtype] = ct[-16:]
            body = ct + b"\x00"  # последний байт области не входит в данные
        else:
            body = content + b"\x00"
        payload = bytes([seed]) + xor(body, xor_key(seed, rtype))
        n = len(payload)
        self.out += bytes([rtype]) + (bytes([n]) if self.version <= 12 else struct.pack("<H", n))
        self.out += payload + b"\xff"

    def raw_record(self, rtype, data):
        n = len(data)
        self.out += bytes([rtype]) + (bytes([n]) if self.version <= 12 else struct.pack("<H", n)) + data + b"\xff"


def details_bytes(start_ms, lat, lon, product_type, name, sn):
    d = bytearray(380)
    struct.pack_into("<20s20s20s20s", d, 0, b"", b"Test street", b"Moscow", b"RU")
    struct.pack_into("<BBBii", d, 80, 0, 1, 0, 1234, 0)
    struct.pack_into("<qddfifff", d, 91, start_ms, lon, lat, 1834.5, 125000, 62.3, 12.4, 3.1)
    struct.pack_into("<f", d, 267, 150.0)
    struct.pack_into("<B", d, 271, product_type)
    struct.pack_into("<32s16s16s16s16s", d, 280, name.encode(), sn.encode(), b"CAM123", b"RC456", b"BAT789")
    struct.pack_into("<B3B", d, 376, 2, 1, 14, 3)
    return bytes(d)


def osd(lon, lat, height, vx, vy, vz, pitch, roll, yaw, mode, motor, gps_num, battery, fly_time, vibrating=False,
        action=0):
    b = bytearray(50)
    struct.pack_into("<dd7h", b, 0, math.radians(lon), math.radians(lat), int(height * 10), int(vx * 10),
                     int(vy * 10), int(vz * 10), int(pitch * 10), int(roll * 10), int(yaw * 10))
    b[30] = mode & 0x7F
    b[32] = (0x08 if motor else 0) | ((2 if height > 0.5 else 0) << 1)
    b[33] = 0x80  # GPS valid
    b[34] = (5 << 2)  # gps level
    b[35] = 0x40 if vibrating else 0
    b[36] = gps_num
    b[37] = action
    b[40] = battery
    struct.pack_into("<H", b, 42, int(fly_time * 10))
    b[48] = 77
    return bytes(b)


def home(lon, lat, alt):
    b = bytearray(41)
    struct.pack_into("<ddf", b, 0, math.radians(lon), math.radians(lat), alt * 10)
    b[20] = 0x01
    struct.pack_into("<H", b, 22, 120)
    struct.pack_into("<f", b, 37, 500.0)
    return bytes(b)


def custom(h_speed, distance, ts_ms):
    return struct.pack("<BBffq", 0, 0, h_speed, distance, ts_ms)


def center_battery(percent, voltage, remaining, current):
    b = bytearray(40)
    struct.pack_into("<BHHHBHIh", b, 0, percent, int(voltage * 1000), remaining, 5000, 95, 37, 0, int(current * 1000))
    for i in range(4):
        struct.pack_into("<H", b, 16 + 2 * i, int(voltage / 4 * 1000))
    struct.pack_into("<H", b, 32, int((30 + 273.15) * 10))
    return bytes(b)


def simulate(w, start_ms, duration=120.0):
    lat0, lon0 = 55.751244, 37.618423
    m_lat = 111320.0
    m_lon = 111320.0 * math.cos(math.radians(lat0))
    w.record(2, home(lon0, lat0, 150.0))
    x = y = h = 0.0
    used = 0.0
    for k in range(int(duration * 10)):
        t = k / 10.0
        motor = 3.0 <= t < duration - 3
        if t < 5:
            mode, th, vx, vy = 41 if motor else 6, 0.0, 0.0, 0.0
        elif t < 15:
            mode, th, vx, vy = 11, (t - 5) * 4.0, 0.0, 0.0
        elif t < duration - 30:
            mode, th, vx, vy = 6, 40.0, 6.0 * math.cos(t / 15), 6.0 * math.sin(t / 15)
        elif t < duration - 12:
            mode, th, vx, vy = 15, 40.0, -x / 15, -y / 15
        else:
            mode, th, vx, vy = 12, max(0.0, 40.0 - (t - (duration - 12)) * 4.5), 0.0, 0.0
        x += vy * 0.1
        y += vx * 0.1
        vz = -(th - h) * 2
        h += (th - h) * 0.2
        current = 18.0 + 0.8 * math.hypot(vx, vy) if h > 0.5 else (2.0 if motor else 0.3)
        used += current * 0.1 / 3.6
        voltage = 16.8 - used / 5000 * 2.4 - current * 0.01
        percent = max(0, int(100 - used / 50))
        vib = 60 <= t < 66
        w.record(5, custom(math.hypot(vx, vy), math.hypot(x, y), start_ms + int(t * 1000)))
        w.record(1, osd(lon0 + x / m_lon, lat0 + y / m_lat, h, vx, vy, vz, -math.hypot(vx, vy) * 2, 1.5,
                        (math.degrees(math.atan2(vy, vx)) + 360) % 360 - 180, mode, motor,
                        9 if 80 <= t < 86 else 18, percent, t, vibrating=vib, action=12 if mode == 15 else 0))
        if k % 5 == 0:
            w.record(7, center_battery(percent, voltage, int(5000 - used), -current))
        if k % 5 in (1, 3):  # как в реальных логах: Home перемежается с OSD в той же группе ключа
            w.record(2, home(lon0, lat0, 150.0))
        if k == 40:
            w.record(9, b"Motors started\x00")
        if k == 620:
            w.record(10, b"Strong winds. Fly with caution\x00")
        if k == int((duration - 30) * 10):
            w.record(24, b"Low battery. Returning to home\x00")


def write_v12(path, start_ms):
    w = Writer(12)
    simulate(w, start_ms)
    d = details_bytes(start_ms, 55.751244, 37.618423, 67, "Mavic Air 2", "SN12XYZ")
    d = d + bytes(436 - len(d))
    prefix = struct.pack("<QHBBQ80s", 100 + 436 + len(w.out), 436, 12, 0, 0, b"")
    with open(path, "wb") as f:
        f.write(prefix + d + w.out)


def write_v14(path, keys_path, start_ms, chain="feature"):
    keys = {
        FEATURE_BASE: [bytes(range(32)), bytes(range(16, 32))],
        FEATURE_CUSTOM: [bytes(range(100, 132)), bytes(range(50, 66))],
        FEATURE_BATTERY: [bytes(range(200, 232)), bytes(range(70, 86))],
    }
    initial = {f: [k, iv] for f, (k, iv) in keys.items()}
    w = Writer(14, {f: [k, iv] for f, (k, iv) in keys.items()}, chain)
    w.initial_iv = {f: iv for f, (k, iv) in keys.items()}
    for f in (FEATURE_BASE, FEATURE_CUSTOM, FEATURE_BATTERY):
        blob = bytes([f]) * 48  # зашифрованный ключ; в реальном логе его расшифровывает сервер DJI
        w.record(56, struct.pack("<HH", f, len(blob)) + blob)
    simulate(w, start_ms)
    info = details_bytes(start_ms, 55.751244, 37.618423, 77, "Mavic 3", "SN14ABC")
    info_struct = struct.pack("<BH", 1, len(info)) + info + struct.pack("<H", 4) + b"SIGN"
    seed = 0x5A
    aux_info = bytes([seed]) + xor(info_struct, xor_key(seed, 0))
    aux = bytes([0]) + struct.pack("<H", len(aux_info)) + aux_info
    aux += bytes([1]) + struct.pack("<H", 3) + struct.pack("<HB", 1, 3)
    records_offset = 100 + len(aux)
    prefix = struct.pack("<QHBBQ80s", records_offset, len(aux), 14, 0, 0, b"")
    with open(path, "wb") as f:
        f.write(prefix + aux + w.out)
    keychains = [[{"featurePoint": FEATURE_NAMES[f], "aesKey": base64.b64encode(k).decode(),
                   "aesIv": base64.b64encode(iv).decode()} for f, (k, iv) in initial.items()]]
    with open(keys_path, "w") as f:
        json.dump(keychains, f, indent=1)


def main():
    os.makedirs(TEST_RES, exist_ok=True)
    start = 1789385525000  # 2026-09-14 11:32:05 UTC
    write_v12(os.path.join(TEST_RES, "dji_v12.txt"), start)
    write_v14(os.path.join(TEST_RES, "dji_v14.txt"), os.path.join(TEST_RES, "dji_v14.keychains.json"), start)
    # Тот же полёт, но цепочка IV по типу записи (встречается в реальных логах v14) — ключи те же.
    write_v14(os.path.join(TEST_RES, "dji_v14_typechain.txt"), os.devnull, start, chain="type")
    for n in ("dji_v12.txt", "dji_v14.txt", "dji_v14_typechain.txt"):
        print(n, os.path.getsize(os.path.join(TEST_RES, n)))


if __name__ == "__main__":
    sys.exit(main())
