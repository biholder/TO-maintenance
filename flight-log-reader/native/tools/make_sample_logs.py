#!/usr/bin/env python3
"""Генератор синтетических полётных логов ArduCopter для тестов и демо-режима.

Пишет DataFlash .bin (формат ArduPilot) и MAVLink .tlog. Корректность файлов
проверяется эталонным ридером pymavlink (DFReader / mavutil).

    pip install pymavlink
    python3 tools/make_sample_logs.py
"""
import math
import os
import random
import struct
import sys
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_RES = os.path.join(ROOT, "core", "src", "test", "resources")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "demo")

FMT_STRUCT = {
    "b": "b", "B": "B", "h": "h", "H": "H", "i": "i", "I": "I", "f": "f",
    "d": "d", "n": "4s", "N": "16s", "Z": "64s", "c": "h", "C": "H",
    "e": "i", "E": "I", "L": "i", "M": "B", "q": "q", "Q": "Q",
}
SCALE = {"c": 100, "C": 100, "e": 100, "E": 100, "L": 1e7}


class DFWriter:
    def __init__(self):
        self.buf = bytearray()
        self.fmts = {}
        self.next_type = 129
        self._fmt_raw(128, "FMT", "BBnNZ", "Type,Length,Name,Format,Columns")

    def _fmt_raw(self, mtype, name, fmt, cols):
        length = 3 + struct.calcsize("<" + "".join(FMT_STRUCT[c] for c in fmt))
        self.fmts[name] = (mtype, fmt)
        if name != "FMT":
            self.write("FMT", mtype, length, name.encode(), fmt.encode(), cols.encode())
        else:
            self.buf += struct.pack("<BBBBB4s16s64s", 0xA3, 0x95, 128, 128, 89,
                                    b"FMT", b"BBnNZ", cols.encode())

    def define(self, name, fmt, cols):
        assert len(fmt) == len(cols.split(",")), name
        self._fmt_raw(self.next_type, name, fmt, cols)
        self.next_type += 1

    def write(self, name, *values):
        mtype, fmt = self.fmts[name]
        out = []
        for c, v in zip(fmt, values):
            if c in SCALE:
                v = int(round(v * SCALE[c]))
            elif c in "nNZ" and isinstance(v, str):
                v = v.encode()
            out.append(v)
        self.buf += struct.pack("<BBB", 0xA3, 0x95, mtype)
        self.buf += struct.pack("<" + "".join(FMT_STRUCT[c] for c in fmt), *out)


PARAMS = [
    # name, value, default
    ("ATC_ANG_PIT_P", 4.5, 4.5), ("ATC_ANG_RLL_P", 4.5, 4.5), ("ATC_ANG_YAW_P", 4.5, 4.5),
    ("ATC_RAT_PIT_D", 0.0036, 0.0036), ("ATC_RAT_PIT_I", 0.135, 0.135), ("ATC_RAT_PIT_P", 0.135, 0.135),
    ("ATC_RAT_RLL_D", 0.0042, 0.0036), ("ATC_RAT_RLL_I", 0.118, 0.135), ("ATC_RAT_RLL_P", 0.118, 0.135),
    ("ATC_RAT_YAW_P", 0.18, 0.18), ("ATC_THR_MIX_MAN", 0.1, 0.1),
    ("BATT_CAPACITY", 16000, 3300), ("BATT_CRT_VOLT", 20.4, 0), ("BATT_FS_CRT_ACT", 1, 0),
    ("BATT_FS_LOW_ACT", 2, 0), ("BATT_LOW_VOLT", 21.0, 10.5), ("BATT_MONITOR", 4, 0),
    ("COMPASS_USE", 1, 1), ("COMPASS_USE2", 1, 1), ("EK3_ENABLE", 1, 1), ("EK3_SRC1_POSXY", 3, 3),
    ("FENCE_ENABLE", 1, 0), ("FENCE_ALT_MAX", 120, 100), ("FENCE_RADIUS", 800, 300),
    ("FLTMODE1", 5, 7), ("FLTMODE2", 2, 9), ("FLTMODE3", 3, 6),
    ("FS_GCS_ENABLE", 0, 0), ("FS_THR_ENABLE", 1, 1), ("FS_EKF_ACTION", 1, 1),
    ("GPS_TYPE", 2, 1), ("INS_ACCEL_FILTER", 20, 20), ("INS_GYRO_FILTER", 40, 20),
    ("INS_HNTCH_ENABLE", 1, 0), ("INS_HNTCH_FREQ", 85, 80), ("INS_HNTCH_REF", 0.21, 0),
    ("LAND_SPEED", 50, 50), ("LOG_BITMASK", 180222, 176126), ("MOT_BAT_VOLT_MAX", 25.2, 0),
    ("MOT_BAT_VOLT_MIN", 19.8, 0), ("MOT_PWM_MAX", 2000, 0), ("MOT_PWM_MIN", 1000, 0),
    ("MOT_SPIN_ARM", 0.08, 0.1), ("MOT_SPIN_MIN", 0.12, 0.15), ("MOT_THST_EXPO", 0.65, 0.65),
    ("MOT_THST_HOVER", 0.21, 0.35), ("RTL_ALT", 3000, 1500), ("RTL_LOIT_TIME", 3000, 5000),
    ("SERIAL1_PROTOCOL", 2, 2), ("WPNAV_ACCEL", 250, 250), ("WPNAV_SPEED", 1200, 1000),
    ("WPNAV_SPEED_DN", 150, 150), ("WPNAV_SPEED_UP", 250, 250),
]

COPTER_MODES = {0: "STABILIZE", 3: "AUTO", 5: "LOITER", 6: "RTL", 9: "LAND"}
GPS_EPOCH = datetime(1980, 1, 6, tzinfo=timezone.utc)


def simulate(seed, duration, start_utc, home, vibe_spike, param_overrides):
    """Возвращает список сэмплов по 10 Гц и список событий (время, тип, данные)."""
    rnd = random.Random(seed)
    lat0, lon0 = home
    m_per_deg_lat = 111320.0
    m_per_deg_lon = 111320.0 * math.cos(math.radians(lat0))
    t_arm, t_to, t_auto, t_rtl, t_land, t_disarm = 20, 28, 45, duration - 95, duration - 45, duration - 12
    cruise_alt = 60.0
    # маршрут «змейка» для AUTO
    legs = []
    for i in range(6):
        y = i * 60.0
        legs += [(0.0, y), (380.0, y)] if i % 2 == 0 else [(380.0, y), (0.0, y)]
    route = [(0.0, 0.0)] + legs
    seg_len = [math.dist(route[i], route[i + 1]) for i in range(len(route) - 1)]
    total = sum(seg_len)

    def pos_on_route(d):
        for i, L in enumerate(seg_len):
            if d <= L:
                a, b = route[i], route[i + 1]
                k = d / L if L else 0
                return a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, math.atan2(b[0] - a[0], b[1] - a[1])
            d -= L
        return route[-1][0], route[-1][1], 0.0

    samples = []
    x = y = 0.0
    alt = 0.0
    yaw = 0.0
    curr_tot = 0.0
    volt_rest = 25.1
    d_auto = 0.0
    rtl_from = None
    prev = (0.0, 0.0, 0.0)
    clip = 0
    for k in range(int(duration * 10) + 1):
        t = k / 10.0
        armed = t_arm <= t < t_disarm
        if t < t_to:
            tx, ty, talt = 0.0, 0.0, 0.0
        elif t < t_auto:
            tx, ty, talt = 0.0, 0.0, min(cruise_alt, (t - t_to) * 3.5)
        elif t < t_rtl:
            d_auto = min(total, (t - t_auto) * (total / (t_rtl - t_auto - 10)))
            tx, ty, yaw = pos_on_route(d_auto)
            talt = cruise_alt
        elif t < t_land:
            if rtl_from is None:
                rtl_from = (x, y)
            k_r = min(1.0, (t - t_rtl) / (t_land - t_rtl - 8))
            tx, ty = rtl_from[0] * (1 - k_r), rtl_from[1] * (1 - k_r)
            talt = cruise_alt
        else:
            tx, ty = 0.0, 0.0
            talt = max(0.0, cruise_alt - (t - t_land) * 1.9)
        x += (tx - x) * 0.6 + rnd.gauss(0, 0.05)
        y += (ty - y) * 0.6 + rnd.gauss(0, 0.05)
        alt += (talt - alt) * 0.5 + (rnd.gauss(0, 0.04) if alt > 0.3 else 0)
        alt = max(alt, 0.0)
        vx, vy, vz = (x - prev[0]) * 10, (y - prev[1]) * 10, (alt - prev[2]) * 10
        prev = (x, y, alt)
        spd = math.hypot(vx, vy)
        flying = alt > 0.5
        curr = (0.6 if armed else 0.35) + ((22.0 + 1.6 * spd + max(vz, 0) * 3.0) if flying else (3.0 if armed else 0))
        curr += rnd.gauss(0, 0.4)
        curr_tot += curr * 0.1 / 3.6
        volt_rest = 25.1 - 3.6 * (curr_tot / 16000.0) ** 1.15 * 2.4
        volt = volt_rest - curr * 0.018 + rnd.gauss(0, 0.02)
        vibe_base = 9.0 if flying else 1.2
        vz_vibe = vibe_base * 1.6 + rnd.gauss(0, 1.2)
        if vibe_spike and vibe_spike[0] <= t <= vibe_spike[1]:
            vz_vibe = 34.0 + 9.0 * math.sin((t - vibe_spike[0]) * 1.3) + rnd.gauss(0, 2)
            if rnd.random() < 0.05:
                clip += 1
        nsats = 16 + int(2 * math.sin(t / 40.0))
        hdop = 0.7 + 0.1 * math.sin(t / 25.0)
        if seed == 1 and 340 <= t <= 352:
            nsats, hdop = 9, 1.6
        roll = math.degrees(math.atan2(-vx * 0.3, 9.8)) * 0.5 + rnd.gauss(0, 0.4)
        pitch = -spd * 0.9 + rnd.gauss(0, 0.4)
        samples.append(dict(
            t=t, armed=armed, lat=lat0 + y / m_per_deg_lat, lon=lon0 + x / m_per_deg_lon,
            alt=alt, spd=spd, vz=vz, gcrs=(math.degrees(math.atan2(vx, vy)) + 360) % 360,
            volt=volt, curr=max(curr, 0), curr_tot=curr_tot, vibe=(vz_vibe * 0.55, vz_vibe * 0.6, vz_vibe),
            clip=clip, nsats=nsats, hdop=hdop, roll=roll, pitch=pitch,
            yaw=(math.degrees(yaw) + 360) % 360, thr=0.21 + (0.03 if spd > 1 else 0) if flying else 0.0,
            rem=max(0, int(100 - curr_tot / 160)),
        ))
    modes = [(0.0, 0), (t_arm - 2, 5), (t_auto, 3), (t_rtl, 6), (t_land, 9)]
    events = [(t_arm, "ARM"), (t_to, "TAKEOFF"), (t_disarm - 3, "LANDED"), (t_disarm, "DISARM")]
    return samples, modes, events, dict(PARAMS_OVERRIDE=param_overrides)


def gps_time(start_utc, t):
    sec = (start_utc - GPS_EPOCH).total_seconds() + 18 + t
    week = int(sec // 604800)
    ms = int(round((sec - week * 604800) * 1000))
    return week, ms


def write_bin(path, seed, duration, start_utc, home, vibe_spike, overrides, board="CubeOrange+"):
    samples, modes, events, _ = simulate(seed, duration, start_utc, home, vibe_spike, overrides)
    w = DFWriter()
    w.define("PARM", "QNff", "TimeUS,Name,Value,Default")
    w.define("MSG", "QZ", "TimeUS,Message")
    w.define("MODE", "QMBB", "TimeUS,Mode,ModeNum,Rsn")
    w.define("EV", "QB", "TimeUS,Id")
    w.define("ERR", "QBB", "TimeUS,Subsys,ECode")
    w.define("GPS", "QBBIHBcLLeffffB", "TimeUS,I,Status,GMS,GWk,NSats,HDop,Lat,Lng,Alt,Spd,GCrs,VZ,Yaw,U")
    w.define("CTUN", "Qffffffefffhh", "TimeUS,ThI,ABst,ThO,ThH,DAlt,Alt,BAlt,DSAlt,SAlt,TAlt,DCRt,CRt")
    w.define("BAT", "QBfffffcB", "TimeUS,Inst,Volt,VoltR,Curr,CurrTot,EnrgTot,Temp,RemPct")
    w.define("VIBE", "QBfffI", "TimeUS,IMU,VibeX,VibeY,VibeZ,Clip")
    w.define("ATT", "QccccCCCCB", "TimeUS,DesRoll,Roll,DesPitch,Pitch,DesYaw,Yaw,ErrRP,ErrYaw,AEKF")

    boot = 41_250_000

    def us(t):
        return boot + int(round(t * 1e6))

    msgs = [
        "ArduCopter V4.5.7 (2a3dc4b7)",
        "ChibiOS: 6a85082c",
        f"{board} 003C0028 3532510F 35383433",
        "RCOut: PWM:1-12",
        "IMU0: fast sampling enabled 8.0kHz/2.0kHz",
        "Frame: QUAD/X",
        "GPS 1: detected u-blox at 230400 baud",
        "u-blox 1 HW: 00190000 SW: EXT CORE 1.00 (61b2dd)",
        "EKF3 IMU0 initialised",
        "EKF3 IMU0 origin set",
        "EKF3 IMU0 is using GPS",
    ]
    for i, m in enumerate(msgs):
        w.write("MSG", us(0.01 * i), m)
    params = {n: (v, d) for n, v, d in PARAMS}
    params.update(overrides)
    for i, (n, (v, d)) in enumerate(sorted(params.items())):
        w.write("PARM", us(0.2 + 0.001 * i), n, float(v), float(d))
    mode_iter = iter(modes)
    next_mode = next(mode_iter, None)
    ev_iter = iter(events)
    next_ev = next(ev_iter, None)
    ev_ids = {"ARM": 10, "DISARM": 11, "LANDED": 18}
    extra_msgs = {
        events[0][0]: "Arming motors",
        events[1][0]: "Mission: 1 Takeoff",
        events[1][0] + 17: "Mission: 2 WP",
        modes[3][0] + 0.1: "Mission complete, changing mode to RTL",
        events[3][0] + 0.1: "Disarming motors",
    }
    if vibe_spike:
        extra_msgs[vibe_spike[0] + 4] = "Vibration compensation ON"
    for s in samples:
        t = s["t"]
        while next_mode and next_mode[0] <= t:
            w.write("MODE", us(next_mode[0]), next_mode[1], next_mode[1], 1)
            next_mode = next(mode_iter, None)
        while next_ev and next_ev[0] <= t:
            if next_ev[1] in ev_ids:
                w.write("EV", us(next_ev[0]), ev_ids[next_ev[1]])
            next_ev = next(ev_iter, None)
        for mt, text in list(extra_msgs.items()):
            if mt <= t:
                w.write("MSG", us(mt), text)
                del extra_msgs[mt]
        k = int(round(t * 10))
        if k % 2 == 0:  # 5 Гц
            week, ms = gps_time(start_utc, t)
            w.write("GPS", us(t), 0, 6 if s["nsats"] >= 12 else 3, ms, week, s["nsats"], s["hdop"],
                    s["lat"], s["lon"], 182.4 + s["alt"], s["spd"], s["gcrs"], -s["vz"], 0.0, 1)
        w.write("CTUN", us(t + 0.002), 0.21, 0.0, s["thr"], 0.21, s["alt"], s["alt"], s["alt"] + 0.1,
                0.0, 0.0, 0.0, int(s["vz"] * 100), int(s["vz"] * 100))
        w.write("ATT", us(t + 0.004), s["roll"], s["roll"] + 0.2, s["pitch"], s["pitch"] - 0.1,
                s["yaw"], s["yaw"], 0.02, 0.01, 1)
        if k % 2 == 1:
            w.write("BAT", us(t), 0, s["volt"], s["volt"] + s["curr"] * 0.018, s["curr"], s["curr_tot"],
                    s["curr_tot"] * 22.8 / 1000, 31.5, s["rem"])
            w.write("VIBE", us(t + 0.001), 0, *s["vibe"], s["clip"])
    if seed == 1:
        w.write("ERR", us(341.0), 11, 2)
        w.write("ERR", us(352.5), 11, 0)
    with open(path, "wb") as f:
        f.write(w.buf)
    return samples


def write_tlog(path, duration, start_utc, home):
    from pymavlink.dialects.v20 import ardupilotmega as mav
    samples, modes, events, _ = simulate(7, duration, start_utc, home, None, {})
    out = open(path, "wb")

    class F:  # pymavlink пишет пакеты в file-like
        def write(self, b):
            pass
    m = mav.MAVLink(F(), srcSystem=1, srcComponent=1)
    gcs = mav.MAVLink(F(), srcSystem=255, srcComponent=190)
    t0 = start_utc.timestamp()

    def emit(link, t, msg):
        out.write(struct.pack(">Q", int((t0 + t) * 1e6)))
        out.write(msg.pack(link))

    mode_at = lambda t: max((mm for mm in modes if mm[0] <= t), key=lambda mm: mm[0])[1]
    emit(m, 0, mav.MAVLink_statustext_message(6, b"ArduCopter V4.5.7 (2a3dc4b7)"))
    emit(m, 0, mav.MAVLink_statustext_message(6, b"Frame: QUAD/X"))
    for i, (n, v, d) in enumerate(PARAMS):
        emit(m, 0.1 + 0.01 * i, mav.MAVLink_param_value_message(n.encode(), float(v), 9, len(PARAMS), i))
    for s in samples:
        t = s["t"]
        k = int(round(t * 10))
        boot = int(t * 1000) + 30000
        if k % 10 == 0:
            base = 0x80 | 0x01 if s["armed"] else 0x01
            emit(m, t, mav.MAVLink_heartbeat_message(2, 3, base, mode_at(t), 4, 3))
            emit(gcs, t, mav.MAVLink_heartbeat_message(6, 8, 0, 0, 0, 3))
            emit(m, t, mav.MAVLink_system_time_message(int((t0 + t) * 1e6), boot))
        if k % 2 == 0:
            emit(m, t, mav.MAVLink_global_position_int_message(
                boot, int(s["lat"] * 1e7), int(s["lon"] * 1e7), int((182.4 + s["alt"]) * 1000),
                int(s["alt"] * 1000), 0, 0, int(-s["vz"] * 100), int(s["yaw"] * 100)))
            emit(m, t, mav.MAVLink_gps_raw_int_message(
                int(t * 1e6), 3, int(s["lat"] * 1e7), int(s["lon"] * 1e7), int((182.4 + s["alt"]) * 1000),
                int(s["hdop"] * 100), 120, int(s["spd"] * 100), int(s["gcrs"] * 100), s["nsats"]))
            emit(m, t, mav.MAVLink_vfr_hud_message(airspeed=0, groundspeed=s["spd"], heading=int(s["yaw"]), throttle=int(s["thr"] * 100), alt=182.4 + s["alt"], climb=s["vz"]))
            emit(m, t, mav.MAVLink_attitude_message(boot, math.radians(s["roll"]), math.radians(s["pitch"]),
                                                     math.radians(s["yaw"] - 360 if s["yaw"] > 180 else s["yaw"]), 0, 0, 0))
        if k % 5 == 0:
            emit(m, t, mav.MAVLink_sys_status_message(
                0, 0, 0, load=300, voltage_battery=int(s["volt"] * 1000), current_battery=int(s["curr"] * 100),
                battery_remaining=s["rem"], drop_rate_comm=0, errors_comm=0, errors_count1=0, errors_count2=0,
                errors_count3=0, errors_count4=0))
            emit(m, t, mav.MAVLink_battery_status_message(0, 1, 1, 3150, [int(s["volt"] * 1000)] + [65535] * 9,
                                                           int(s["curr"] * 100), int(s["curr_tot"]), -1, s["rem"]))
            emit(m, t, mav.MAVLink_vibration_message(int(t * 1e6), *s["vibe"], s["clip"], 0, 0))
    out.close()


def verify_bin(path):
    from pymavlink import DFReader
    r = DFReader.DFReader_binary(path)
    counts = {}
    while True:
        m = r.recv_msg()
        if m is None:
            break
        counts[m.get_type()] = counts.get(m.get_type(), 0) + 1
    return counts


def verify_tlog(path):
    from pymavlink import mavutil
    r = mavutil.mavlink_connection(path, dialect="ardupilotmega")
    counts = {}
    while True:
        m = r.recv_match()
        if m is None:
            break
        counts[m.get_type()] = counts.get(m.get_type(), 0) + 1
    return counts


def write_expected(bin_path, tlog_path, out_path):
    """Эталонные значения, посчитанные pymavlink, — для сверки в JUnit-тестах."""
    from pymavlink import DFReader, mavutil
    r = DFReader.DFReader_binary(bin_path)
    exp = {}
    counts = {}
    alt_max, volt_min, vibe_max, curr_tot, first_gps, params = 0.0, 99.0, 0.0, 0.0, None, {}
    while True:
        m = r.recv_msg()
        if m is None:
            break
        t = m.get_type()
        counts[t] = counts.get(t, 0) + 1
        if t == "CTUN":
            alt_max = max(alt_max, m.Alt)
        elif t == "BAT":
            volt_min = min(volt_min, m.Volt)
            curr_tot = m.CurrTot
        elif t == "VIBE":
            vibe_max = max(vibe_max, m.VibeZ)
        elif t == "GPS" and first_gps is None:
            first_gps = (m.Lat, m.Lng, m.GWk, m.GMS)
        elif t == "PARM":
            params[m.Name] = m.Value
    for k, v in counts.items():
        exp["bin.count." + k] = v
    exp.update({"bin.ctun.alt.max": alt_max, "bin.bat.volt.min": volt_min, "bin.vibe.z.max": vibe_max,
                "bin.bat.currtot.last": curr_tot, "bin.gps.first.lat": first_gps[0],
                "bin.gps.first.lng": first_gps[1], "bin.gps.first.gwk": first_gps[2],
                "bin.gps.first.gms": first_gps[3], "bin.parm.ATC_RAT_RLL_P": params["ATC_RAT_RLL_P"]})
    r = mavutil.mavlink_connection(tlog_path, dialect="ardupilotmega")
    tc = {}
    rel_max, volt_min, sats_min = 0, 99.0, 99
    while True:
        m = r.recv_match()
        if m is None:
            break
        if m.get_srcSystem() != 1:
            continue
        t = m.get_type()
        tc[t] = tc.get(t, 0) + 1
        if t == "GLOBAL_POSITION_INT":
            rel_max = max(rel_max, m.relative_alt / 1000.0)
        elif t == "SYS_STATUS":
            volt_min = min(volt_min, m.voltage_battery / 1000.0)
        elif t == "GPS_RAW_INT":
            sats_min = min(sats_min, m.satellites_visible)
    for k, v in tc.items():
        exp["tlog.count." + k] = v
    exp.update({"tlog.relalt.max": rel_max, "tlog.volt.min": volt_min, "tlog.sats.min": sats_min})
    with open(out_path, "w") as f:
        f.write("# Сгенерировано tools/make_sample_logs.py (pymavlink)\n")
        for k in sorted(exp):
            f.write("%s=%r\n" % (k, exp[k]))


def main():
    os.makedirs(TEST_RES, exist_ok=True)
    os.makedirs(ASSETS, exist_ok=True)
    start_a = datetime(2026, 9, 14, 11, 32, 5, tzinfo=timezone.utc)
    start_b = datetime(2026, 9, 12, 8, 10, 40, tzinfo=timezone.utc)
    home = (55.927140, 37.513820)
    a = os.path.join(ASSETS, "00000042.BIN")
    b = os.path.join(ASSETS, "00000039.BIN")
    write_bin(a, 1, 640.0, start_a, home, (262.0, 278.0), {})
    write_bin(b, 2, 600.0, start_b, home, None,
              {"ATC_RAT_RLL_P": (0.135, 0.135), "ATC_RAT_RLL_I": (0.135, 0.135),
               "INS_HNTCH_ENABLE": (0, 0), "MOT_THST_HOVER": (0.24, 0.35)})
    small = os.path.join(TEST_RES, "copter_small.bin")
    write_bin(small, 1, 400.0, start_a, home, (262.0, 278.0), {})
    tlog = os.path.join(TEST_RES, "copter_small.tlog")
    write_tlog(tlog, 180.0, start_a, home)
    for p in (a, b, small):
        print(p, os.path.getsize(p), verify_bin(p))
    print(tlog, os.path.getsize(tlog), verify_tlog(tlog))
    write_expected(small, tlog, os.path.join(TEST_RES, "expected.properties"))


if __name__ == "__main__":
    sys.exit(main())
