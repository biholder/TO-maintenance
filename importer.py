"""Импорт данных о дронах, полётах и запчастях из CSV/XLSX таблиц."""
import io
import re
from datetime import datetime

import pandas as pd

from db import db
from models import Drone, Flight, Part, Tracker

DRONE_STATUS_ALIASES = {
    "активен": "active",
    "в строю": "active",
    "active": "active",
    "на обслуживании": "maintenance",
    "в обслуживании": "maintenance",
    "обслуживание": "maintenance",
    "maintenance": "maintenance",
    "списан": "written_off",
    "списано": "written_off",
    "written_off": "written_off",
}

PART_STATUS_ALIASES = {
    "на складе": "in_stock",
    "склад": "in_stock",
    "in_stock": "in_stock",
    "установлена": "installed",
    "установлен": "installed",
    "installed": "installed",
    "списана": "written_off",
    "списан": "written_off",
    "written_off": "written_off",
}

TRACKER_STATUS_ALIASES = {
    "не активирован": "not_activated",
    "неактивен": "not_activated",
    "not_activated": "not_activated",
    "активирован": "activated",
    "активен": "activated",
    "activated": "activated",
    "приостановлен": "suspended",
    "приостановлена": "suspended",
    "suspended": "suspended",
}

DRONE_HEADERS = {
    "тип": "type",
    "модель": "type",
    "бортовой номер": "tail_number",
    "борт": "tail_number",
    "серийный номер": "serial_number",
    "заводской номер": "serial_number",
    "наработка (ч)": "initial_hours",
    "наработка ч": "initial_hours",
    "наработка часы": "initial_hours",
    "наработка (циклы)": "initial_cycles",
    "наработка циклы": "initial_cycles",
    "циклы": "initial_cycles",
    "дата ввода в эксплуатацию": "commissioned_date",
    "дата ввода": "commissioned_date",
    "статус": "status",
    "примечания": "notes",
    "примечание": "notes",
}

FLIGHT_HEADERS = {
    "бортовой номер": "tail_number",
    "борт": "tail_number",
    "дата": "date",
    "наработка за полёт (ч)": "duration_hours",
    "наработка за полет (ч)": "duration_hours",
    "длительность (ч)": "duration_hours",
    "часы": "duration_hours",
    "пилот": "pilot",
    "примечания": "notes",
    "примечание": "notes",
}

PART_HEADERS = {
    "наименование": "name",
    "название": "name",
    "серийный номер": "serial_number",
    "ресурс (ч)": "resource_hours",
    "ресурс ч": "resource_hours",
    "ресурс (циклы)": "resource_cycles",
    "ресурс циклы": "resource_cycles",
    "бортовой номер": "tail_number",
    "борт": "tail_number",
    "наработка до установки (ч)": "hours_before_install",
    "наработка до установки": "hours_before_install",
    "наработка борта при установке (ч)": "drone_hours_at_install",
    "наработка борта при установке": "drone_hours_at_install",
    "дата установки": "installed_date",
    "статус": "status",
    "примечания": "notes",
    "примечание": "notes",
}

TRACKER_HEADERS = {
    "наименование": "name",
    "название": "name",
    "серийный номер": "serial_number",
    "imei": "serial_number",
    "бортовой номер": "tail_number",
    "борт": "tail_number",
    "статус": "status",
    "дата активации": "activation_date",
    "поставщик": "provider",
    "оператор": "provider",
    "номер договора": "contract_number",
    "договор": "contract_number",
    "начало договора": "contract_start",
    "дата начала договора": "contract_start",
    "окончание договора": "contract_end",
    "дата окончания договора": "contract_end",
    "срок действия договора": "contract_end",
    "примечания": "notes",
    "примечание": "notes",
}


def _norm(text):
    text = str(text).lstrip("﻿")
    return re.sub(r"\s+", " ", text.strip().lower())


def _get_str(row, key):
    """Достаёт строковое поле из строки таблицы, корректно обрабатывая
    пустые ячейки: pandas читает их как NaN (float), а `NaN or ""` в Python
    истинно, поэтому наивная проверка `row.get(key) or ""` превращала бы
    пустую ячейку в строку "nan"."""
    value = row.get(key)
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return None
    text = str(value).strip()
    return text or None


def _read_table(file_storage):
    filename = (file_storage.filename or "").lower()
    raw = file_storage.read()
    if filename.endswith(".xlsx") or filename.endswith(".xls"):
        return pd.read_excel(io.BytesIO(raw), dtype=str)
    return pd.read_csv(io.BytesIO(raw), dtype=str, sep=None, engine="python", encoding="utf-8-sig")


def _map_columns(df, headers_map):
    rename = {}
    for col in df.columns:
        key = _norm(col)
        if key in headers_map:
            rename[col] = headers_map[key]
    return df.rename(columns=rename)


def _parse_float(value):
    if value is None or (isinstance(value, float) and pd.isna(value)) or str(value).strip() == "":
        return None
    text = str(value).strip().replace(",", ".")
    return float(text)


def _parse_int(value):
    f = _parse_float(value)
    return int(f) if f is not None else None


def _parse_date(value):
    if value is None or (isinstance(value, float) and pd.isna(value)) or str(value).strip() == "":
        return None
    text = str(value).strip()
    for fmt in ("%d.%m.%Y", "%Y-%m-%d", "%d/%m/%Y", "%d.%m.%y"):
        try:
            return datetime.strptime(text, fmt).date()
        except ValueError:
            continue
    try:
        return pd.to_datetime(text).date()
    except Exception:
        raise ValueError(f"не удалось распознать дату «{text}»")


def import_drones(file_storage):
    df = _map_columns(_read_table(file_storage), DRONE_HEADERS)
    created, errors = 0, []
    for i, row in df.iterrows():
        row_num = i + 2
        try:
            tail_number = _get_str(row, "tail_number") or ""
            if not tail_number:
                raise ValueError("не указан бортовой номер")
            if Drone.query.filter_by(tail_number=tail_number).first():
                raise ValueError(f"дрон с бортовым номером «{tail_number}» уже существует")
            drone_type = _get_str(row, "type") or ""
            if not drone_type:
                raise ValueError("не указан тип/модель")

            status_raw = _norm(_get_str(row, "status") or "")
            status = DRONE_STATUS_ALIASES.get(status_raw, "active")

            drone = Drone(
                tail_number=tail_number,
                type=drone_type,
                serial_number=_get_str(row, "serial_number"),
                status=status,
                initial_hours=_parse_float(row.get("initial_hours")) or 0.0,
                initial_cycles=_parse_int(row.get("initial_cycles")) or 0,
                commissioned_date=_parse_date(row.get("commissioned_date")),
                notes=_get_str(row, "notes"),
            )
            db.session.add(drone)
            created += 1
        except Exception as exc:
            errors.append(f"Строка {row_num}: {exc}")
    if created:
        db.session.commit()
    else:
        db.session.rollback()
    return created, errors


def import_flights(file_storage):
    df = _map_columns(_read_table(file_storage), FLIGHT_HEADERS)
    created, errors = 0, []
    for i, row in df.iterrows():
        row_num = i + 2
        try:
            tail_number = _get_str(row, "tail_number") or ""
            if not tail_number:
                raise ValueError("не указан бортовой номер")
            drone = Drone.query.filter_by(tail_number=tail_number).first()
            if not drone:
                raise ValueError(f"дрон с бортовым номером «{tail_number}» не найден")

            date_val = _parse_date(row.get("date"))
            if not date_val:
                raise ValueError("не указана дата полёта")

            duration = _parse_float(row.get("duration_hours"))
            if duration is None:
                raise ValueError("не указана наработка за полёт")

            flight = Flight(
                drone_id=drone.id,
                date=date_val,
                duration_hours=duration,
                pilot=_get_str(row, "pilot"),
                notes=_get_str(row, "notes"),
            )
            db.session.add(flight)
            created += 1
        except Exception as exc:
            errors.append(f"Строка {row_num}: {exc}")
    if created:
        db.session.commit()
    else:
        db.session.rollback()
    return created, errors


def import_parts(file_storage):
    df = _map_columns(_read_table(file_storage), PART_HEADERS)
    created, errors = 0, []
    for i, row in df.iterrows():
        row_num = i + 2
        try:
            name = _get_str(row, "name") or ""
            if not name:
                raise ValueError("не указано наименование запчасти")

            tail_number = _get_str(row, "tail_number") or ""
            drone = None
            if tail_number:
                drone = Drone.query.filter_by(tail_number=tail_number).first()
                if not drone:
                    raise ValueError(f"дрон с бортовым номером «{tail_number}» не найден")

            status_raw = _norm(_get_str(row, "status") or "")
            status = PART_STATUS_ALIASES.get(status_raw, "installed" if drone else "in_stock")

            part = Part(
                name=name,
                serial_number=_get_str(row, "serial_number"),
                resource_hours=_parse_float(row.get("resource_hours")),
                resource_cycles=_parse_int(row.get("resource_cycles")),
                status=status,
                hours_before_install=_parse_float(row.get("hours_before_install")) or 0.0,
                notes=_get_str(row, "notes"),
            )
            if drone and status == "installed":
                part.drone_id = drone.id
                part.installed_date = _parse_date(row.get("installed_date")) or datetime.utcnow().date()
                dh = _parse_float(row.get("drone_hours_at_install"))
                part.drone_hours_at_install = dh if dh is not None else drone.flight_hours
                part.drone_cycles_at_install = drone.flight_cycles
            db.session.add(part)
            created += 1
        except Exception as exc:
            errors.append(f"Строка {row_num}: {exc}")
    if created:
        db.session.commit()
    else:
        db.session.rollback()
    return created, errors


def import_trackers(file_storage):
    df = _map_columns(_read_table(file_storage), TRACKER_HEADERS)
    created, errors = 0, []
    for i, row in df.iterrows():
        row_num = i + 2
        try:
            name = _get_str(row, "name") or ""
            if not name:
                raise ValueError("не указано наименование трекера")

            tail_number = _get_str(row, "tail_number") or ""
            drone = None
            if tail_number:
                drone = Drone.query.filter_by(tail_number=tail_number).first()
                if not drone:
                    raise ValueError(f"дрон с бортовым номером «{tail_number}» не найден")

            status_raw = _norm(_get_str(row, "status") or "")
            status = TRACKER_STATUS_ALIASES.get(status_raw, "not_activated")

            tracker = Tracker(
                name=name,
                serial_number=_get_str(row, "serial_number"),
                drone_id=drone.id if drone else None,
                status=status,
                activation_date=_parse_date(row.get("activation_date")),
                provider=_get_str(row, "provider"),
                contract_number=_get_str(row, "contract_number"),
                contract_start=_parse_date(row.get("contract_start")),
                contract_end=_parse_date(row.get("contract_end")),
                notes=_get_str(row, "notes"),
            )
            db.session.add(tracker)
            created += 1
        except Exception as exc:
            errors.append(f"Строка {row_num}: {exc}")
    if created:
        db.session.commit()
    else:
        db.session.rollback()
    return created, errors


IMPORTERS = {
    "drones": import_drones,
    "flights": import_flights,
    "parts": import_parts,
    "trackers": import_trackers,
}

TEMPLATE_HEADERS = {
    "drones": ["Бортовой номер", "Тип", "Серийный номер", "Наработка (ч)", "Наработка (циклы)",
                "Дата ввода в эксплуатацию", "Статус", "Примечания"],
    "flights": ["Бортовой номер", "Дата", "Наработка за полёт (ч)", "Пилот", "Примечания"],
    "parts": ["Наименование", "Серийный номер", "Ресурс (ч)", "Ресурс (циклы)", "Бортовой номер",
               "Наработка до установки (ч)", "Наработка борта при установке (ч)", "Дата установки",
               "Статус", "Примечания"],
    "trackers": ["Наименование", "Серийный номер", "Бортовой номер", "Статус", "Дата активации",
                 "Поставщик", "Номер договора", "Начало договора", "Окончание договора", "Примечания"],
}
