import csv
import io
import os
import sys
from datetime import date, datetime

from flask import Flask, flash, redirect, render_template, request, Response, url_for

from db import db
from importer import IMPORTERS, TEMPLATE_HEADERS
from models import (
    DRONE_STATUSES,
    PART_STATUSES,
    SERVICE_CATEGORIES,
    Drone,
    Flight,
    Part,
    ServiceRecord,
)

# При обычном запуске (python app.py) — папка с исходниками.
# При запуске из собранного PyInstaller .exe — временная папка распаковки
# (там же лежат шаблоны/статика, добавленные через --add-data).
BASE_DIR = getattr(sys, "_MEIPASS", os.path.abspath(os.path.dirname(__file__)))

# База данных должна жить рядом с .exe (а не во временной папке, которая
# удаляется после закрытия программы), поэтому путь для неё считаем отдельно.
if getattr(sys, "frozen", False):
    DATA_DIR = os.path.dirname(sys.executable)
else:
    DATA_DIR = os.path.abspath(os.path.dirname(__file__))


def create_app():
    app = Flask(
        __name__,
        template_folder=os.path.join(BASE_DIR, "templates"),
        static_folder=os.path.join(BASE_DIR, "static"),
    )
    app.config["SECRET_KEY"] = os.environ.get("SECRET_KEY", "dev-secret-key")
    app.config["SQLALCHEMY_DATABASE_URI"] = "sqlite:///" + os.path.join(
        DATA_DIR, "instance", "fleet.db"
    )
    app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False

    os.makedirs(os.path.join(DATA_DIR, "instance"), exist_ok=True)
    db.init_app(app)

    with app.app_context():
        db.create_all()

    register_routes(app)
    return app


def parse_date_field(value, default=None):
    if not value:
        return default
    try:
        return datetime.strptime(value, "%Y-%m-%d").date()
    except ValueError:
        return default


def parse_float_field(value):
    if value in (None, ""):
        return None
    return float(str(value).replace(",", "."))


def register_routes(app):
    @app.context_processor
    def inject_globals():
        return {
            "DRONE_STATUSES": DRONE_STATUSES,
            "PART_STATUSES": PART_STATUSES,
            "SERVICE_CATEGORIES": SERVICE_CATEGORIES,
            "today": date.today().isoformat(),
        }

    # ---------- Дашборд ----------
    @app.route("/")
    def dashboard():
        drones = Drone.query.all()
        total = len(drones)
        by_status = {key: 0 for key in DRONE_STATUSES}
        for d in drones:
            by_status[d.status] = by_status.get(d.status, 0) + 1

        parts = Part.query.filter_by(status="installed").all()
        attention_parts = sorted(
            [p for p in parts if p.resource_status in ("warning", "overdue")],
            key=lambda p: (p.remaining_hours if p.remaining_hours is not None else 10**9),
        )

        recent_flights = Flight.query.order_by(Flight.date.desc(), Flight.id.desc()).limit(8).all()

        return render_template(
            "dashboard.html",
            total=total,
            by_status=by_status,
            attention_parts=attention_parts,
            recent_flights=recent_flights,
        )

    # ---------- Дроны ----------
    @app.route("/drones")
    def drones_list():
        status_filter = request.args.get("status", "")
        type_filter = request.args.get("type", "")
        query = Drone.query
        if status_filter:
            query = query.filter_by(status=status_filter)
        if type_filter:
            query = query.filter_by(type=type_filter)
        drones = query.order_by(Drone.tail_number).all()
        types = sorted({d.type for d in Drone.query.all()})
        return render_template(
            "drones.html", drones=drones, types=types,
            status_filter=status_filter, type_filter=type_filter,
        )

    @app.route("/drones/new", methods=["GET", "POST"])
    def drone_new():
        types = sorted({d.type for d in Drone.query.all()})
        if request.method == "POST":
            tail_number = request.form.get("tail_number", "").strip()
            drone_type = request.form.get("type", "").strip()
            if not tail_number or not drone_type:
                flash("Укажите бортовой номер и тип дрона.", "error")
                return render_template("drone_form.html", drone=None, form=request.form, drones_types=types)
            if Drone.query.filter_by(tail_number=tail_number).first():
                flash(f"Дрон с бортовым номером «{tail_number}» уже существует.", "error")
                return render_template("drone_form.html", drone=None, form=request.form, drones_types=types)

            drone = Drone(
                tail_number=tail_number,
                type=drone_type,
                serial_number=request.form.get("serial_number", "").strip() or None,
                status=request.form.get("status", "active"),
                initial_hours=parse_float_field(request.form.get("initial_hours")) or 0.0,
                initial_cycles=int(request.form.get("initial_cycles") or 0),
                commissioned_date=parse_date_field(request.form.get("commissioned_date")),
                notes=request.form.get("notes", "").strip() or None,
            )
            db.session.add(drone)
            db.session.commit()
            flash(f"Дрон «{tail_number}» добавлен.", "success")
            return redirect(url_for("drone_detail", drone_id=drone.id))
        return render_template("drone_form.html", drone=None, form={}, drones_types=types)

    @app.route("/drones/<int:drone_id>")
    def drone_detail(drone_id):
        drone = Drone.query.get_or_404(drone_id)
        return render_template("drone_detail.html", drone=drone)

    @app.route("/drones/<int:drone_id>/edit", methods=["GET", "POST"])
    def drone_edit(drone_id):
        drone = Drone.query.get_or_404(drone_id)
        if request.method == "POST":
            tail_number = request.form.get("tail_number", "").strip()
            drone_type = request.form.get("type", "").strip()
            if not tail_number or not drone_type:
                flash("Укажите бортовой номер и тип дрона.", "error")
                return render_template("drone_form.html", drone=drone, form=request.form)
            existing = Drone.query.filter_by(tail_number=tail_number).first()
            if existing and existing.id != drone.id:
                flash(f"Дрон с бортовым номером «{tail_number}» уже существует.", "error")
                return render_template("drone_form.html", drone=drone, form=request.form)

            drone.tail_number = tail_number
            drone.type = drone_type
            drone.serial_number = request.form.get("serial_number", "").strip() or None
            drone.status = request.form.get("status", "active")
            drone.initial_hours = parse_float_field(request.form.get("initial_hours")) or 0.0
            drone.initial_cycles = int(request.form.get("initial_cycles") or 0)
            drone.commissioned_date = parse_date_field(request.form.get("commissioned_date"))
            drone.notes = request.form.get("notes", "").strip() or None
            db.session.commit()
            flash("Изменения сохранены.", "success")
            return redirect(url_for("drone_detail", drone_id=drone.id))
        return render_template("drone_form.html", drone=drone, form=None)

    @app.route("/drones/<int:drone_id>/delete", methods=["POST"])
    def drone_delete(drone_id):
        drone = Drone.query.get_or_404(drone_id)
        for part in drone.parts:
            part.drone_id = None
            part.status = "in_stock"
        db.session.delete(drone)
        db.session.commit()
        flash("Дрон удалён.", "success")
        return redirect(url_for("drones_list"))

    # ---------- Полёты ----------
    @app.route("/flights")
    def flights_list():
        drone_id = request.args.get("drone_id", type=int)
        query = Flight.query
        if drone_id:
            query = query.filter_by(drone_id=drone_id)
        flights = query.order_by(Flight.date.desc(), Flight.id.desc()).all()
        drones = Drone.query.order_by(Drone.tail_number).all()
        return render_template("flights.html", flights=flights, drones=drones, drone_id=drone_id)

    @app.route("/flights/new", methods=["GET", "POST"])
    def flight_new():
        drones = Drone.query.order_by(Drone.tail_number).all()
        if request.method == "POST":
            drone_id = request.form.get("drone_id", type=int)
            duration = parse_float_field(request.form.get("duration_hours"))
            flight_date = parse_date_field(request.form.get("date"), default=date.today())
            if not drone_id or duration is None:
                flash("Укажите дрон и наработку за полёт.", "error")
                return render_template("flight_form.html", drones=drones, form=request.form)

            flight = Flight(
                drone_id=drone_id,
                date=flight_date,
                duration_hours=duration,
                pilot=request.form.get("pilot", "").strip() or None,
                notes=request.form.get("notes", "").strip() or None,
            )
            db.session.add(flight)
            db.session.commit()
            flash("Полёт добавлен.", "success")
            return redirect(url_for("flights_list"))
        preselect = request.args.get("drone_id", type=int)
        form = {"drone_id": preselect} if preselect else {}
        return render_template("flight_form.html", drones=drones, form=form)

    @app.route("/flights/<int:flight_id>/delete", methods=["POST"])
    def flight_delete(flight_id):
        flight = Flight.query.get_or_404(flight_id)
        drone_id = flight.drone_id
        db.session.delete(flight)
        db.session.commit()
        flash("Запись о полёте удалена.", "success")
        return redirect(url_for("flights_list", drone_id=drone_id))

    # ---------- Запчасти ----------
    @app.route("/parts")
    def parts_list():
        status_filter = request.args.get("status", "")
        query = Part.query
        if status_filter:
            query = query.filter_by(status=status_filter)
        parts = query.order_by(Part.name).all()
        all_drones = Drone.query.order_by(Drone.tail_number).all()
        return render_template("parts.html", parts=parts, status_filter=status_filter, all_drones=all_drones)

    @app.route("/parts/new", methods=["GET", "POST"])
    def part_new():
        drones = Drone.query.order_by(Drone.tail_number).all()
        if request.method == "POST":
            name = request.form.get("name", "").strip()
            if not name:
                flash("Укажите наименование запчасти.", "error")
                return render_template("part_form.html", part=None, drones=drones, form=request.form)

            drone_id = request.form.get("drone_id", type=int)
            status = request.form.get("status", "in_stock")
            part = Part(
                name=name,
                serial_number=request.form.get("serial_number", "").strip() or None,
                resource_hours=parse_float_field(request.form.get("resource_hours")),
                resource_cycles=int(request.form.get("resource_cycles")) if request.form.get("resource_cycles") else None,
                hours_before_install=parse_float_field(request.form.get("hours_before_install")) or 0.0,
                notes=request.form.get("notes", "").strip() or None,
                status="in_stock",
            )
            db.session.add(part)
            if drone_id and status == "installed":
                drone = Drone.query.get(drone_id)
                if drone:
                    part.drone_id = drone.id
                    part.status = "installed"
                    part.installed_date = date.today()
                    part.drone_hours_at_install = drone.flight_hours
                    part.drone_cycles_at_install = drone.flight_cycles
            db.session.commit()
            flash(f"Запчасть «{name}» добавлена.", "success")
            return redirect(url_for("parts_list"))
        return render_template("part_form.html", part=None, drones=drones, form={})

    @app.route("/parts/<int:part_id>/edit", methods=["GET", "POST"])
    def part_edit(part_id):
        part = Part.query.get_or_404(part_id)
        drones = Drone.query.order_by(Drone.tail_number).all()
        if request.method == "POST":
            name = request.form.get("name", "").strip()
            if not name:
                flash("Укажите наименование запчасти.", "error")
                return render_template("part_form.html", part=part, drones=drones, form=request.form)
            part.name = name
            part.serial_number = request.form.get("serial_number", "").strip() or None
            part.resource_hours = parse_float_field(request.form.get("resource_hours"))
            part.resource_cycles = int(request.form.get("resource_cycles")) if request.form.get("resource_cycles") else None
            part.notes = request.form.get("notes", "").strip() or None
            db.session.commit()
            flash("Изменения сохранены.", "success")
            return redirect(url_for("parts_list"))
        return render_template("part_form.html", part=part, drones=drones, form=None)

    @app.route("/parts/<int:part_id>/install", methods=["POST"])
    def part_install(part_id):
        part = Part.query.get_or_404(part_id)
        drone_id = request.form.get("drone_id", type=int)
        drone = Drone.query.get_or_404(drone_id)
        part.drone_id = drone.id
        part.status = "installed"
        part.installed_date = date.today()
        part.drone_hours_at_install = drone.flight_hours
        part.drone_cycles_at_install = drone.flight_cycles
        db.session.add(ServiceRecord(
            drone_id=drone.id,
            date=date.today(),
            hours_at_event=drone.flight_hours,
            category="part_replacement",
            description=f"Установлена запчасть: {part.name}" + (f" (с/н {part.serial_number})" if part.serial_number else ""),
            part_id=part.id,
        ))
        db.session.commit()
        flash(f"Запчасть «{part.name}» установлена на {drone.tail_number}.", "success")
        return redirect(request.referrer or url_for("parts_list"))

    @app.route("/parts/<int:part_id>/remove", methods=["POST"])
    def part_remove(part_id):
        part = Part.query.get_or_404(part_id)
        drone = part.drone
        write_off = request.form.get("write_off") == "1"
        if drone:
            db.session.add(ServiceRecord(
                drone_id=drone.id,
                date=date.today(),
                hours_at_event=drone.flight_hours,
                category="part_replacement",
                description=f"Снята запчасть: {part.name}" + (f" (с/н {part.serial_number})" if part.serial_number else ""),
                part_id=part.id,
            ))
        if not write_off:
            part.hours_before_install = part.current_hours
            part.cycles_before_install = part.current_cycles
        part.drone_id = None
        part.drone_hours_at_install = None
        part.drone_cycles_at_install = None
        part.status = "written_off" if write_off else "in_stock"
        db.session.commit()
        flash("Запчасть снята с дрона.", "success")
        return redirect(request.referrer or url_for("parts_list"))

    @app.route("/parts/<int:part_id>/delete", methods=["POST"])
    def part_delete(part_id):
        part = Part.query.get_or_404(part_id)
        db.session.delete(part)
        db.session.commit()
        flash("Запчасть удалена.", "success")
        return redirect(url_for("parts_list"))

    # ---------- Обслуживание ----------
    @app.route("/drones/<int:drone_id>/service/new", methods=["POST"])
    def service_new(drone_id):
        drone = Drone.query.get_or_404(drone_id)
        record = ServiceRecord(
            drone_id=drone.id,
            date=parse_date_field(request.form.get("date"), default=date.today()),
            hours_at_event=parse_float_field(request.form.get("hours_at_event")) or drone.flight_hours,
            category=request.form.get("category", "other"),
            description=request.form.get("description", "").strip() or None,
        )
        db.session.add(record)
        db.session.commit()
        flash("Запись о работах добавлена.", "success")
        return redirect(url_for("drone_detail", drone_id=drone.id))

    @app.route("/service/<int:record_id>/delete", methods=["POST"])
    def service_delete(record_id):
        record = ServiceRecord.query.get_or_404(record_id)
        drone_id = record.drone_id
        db.session.delete(record)
        db.session.commit()
        flash("Запись удалена.", "success")
        return redirect(url_for("drone_detail", drone_id=drone_id))

    # ---------- Импорт ----------
    @app.route("/import", methods=["GET", "POST"])
    def import_data():
        if request.method == "POST":
            kind = request.form.get("kind")
            file = request.files.get("file")
            if kind not in IMPORTERS:
                flash("Неизвестный тип импорта.", "error")
                return redirect(url_for("import_data"))
            if not file or not file.filename:
                flash("Выберите файл для импорта.", "error")
                return redirect(url_for("import_data"))
            try:
                created, errors = IMPORTERS[kind](file)
            except Exception as exc:
                flash(f"Не удалось прочитать файл: {exc}", "error")
                return redirect(url_for("import_data"))

            if created:
                flash(f"Импортировано записей: {created}.", "success")
            if errors:
                flash(f"Пропущено строк с ошибками: {len(errors)}.", "error")
            return render_template("import.html", result_errors=errors, result_created=created, last_kind=kind)
        return render_template("import.html")

    @app.route("/import/template/<kind>.csv")
    def import_template(kind):
        if kind not in TEMPLATE_HEADERS:
            return redirect(url_for("import_data"))
        buf = io.StringIO()
        buf.write("﻿")
        writer = csv.writer(buf, delimiter=";")
        writer.writerow(TEMPLATE_HEADERS[kind])
        return Response(
            buf.getvalue(),
            mimetype="text/csv",
            headers={"Content-Disposition": f"attachment; filename={kind}_template.csv"},
        )


app = create_app()

if __name__ == "__main__":
    app.run(debug=True, host="0.0.0.0", port=5000)
