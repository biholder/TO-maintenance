from datetime import datetime

from db import db

DRONE_STATUSES = {
    "active": "В строю",
    "maintenance": "На обслуживании",
    "written_off": "Списан",
}

PART_STATUSES = {
    "in_stock": "На складе",
    "installed": "Установлена",
    "written_off": "Списана",
}

SERVICE_CATEGORIES = {
    "inspection": "Осмотр",
    "repair": "Ремонт",
    "part_replacement": "Замена запчасти",
    "scheduled": "Плановое ТО",
    "other": "Другое",
}

# Доля оставшегося ресурса, при которой запчасть считается "требует внимания"
WARNING_RATIO = 0.1


class Drone(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    tail_number = db.Column(db.String(50), unique=True, nullable=False)
    type = db.Column(db.String(100), nullable=False)
    serial_number = db.Column(db.String(100))
    status = db.Column(db.String(20), default="active", nullable=False)
    initial_hours = db.Column(db.Float, default=0.0, nullable=False)
    initial_cycles = db.Column(db.Integer, default=0, nullable=False)
    commissioned_date = db.Column(db.Date)
    notes = db.Column(db.Text)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)

    flights = db.relationship(
        "Flight",
        backref="drone",
        cascade="all, delete-orphan",
        order_by="Flight.date.desc()",
    )
    parts = db.relationship("Part", backref="drone", order_by="Part.name")
    service_records = db.relationship(
        "ServiceRecord",
        backref="drone",
        cascade="all, delete-orphan",
        order_by="ServiceRecord.date.desc()",
    )

    @property
    def flight_hours(self):
        return round((self.initial_hours or 0) + sum(f.duration_hours for f in self.flights), 2)

    @property
    def flight_cycles(self):
        return (self.initial_cycles or 0) + len(self.flights)

    @property
    def status_label(self):
        return DRONE_STATUSES.get(self.status, self.status)

    @property
    def active_parts(self):
        return [p for p in self.parts if p.status == "installed"]

    @property
    def worst_part_status(self):
        statuses = [p.resource_status for p in self.active_parts]
        if "overdue" in statuses:
            return "overdue"
        if "warning" in statuses:
            return "warning"
        return "ok"


class Flight(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    drone_id = db.Column(db.Integer, db.ForeignKey("drone.id"), nullable=False)
    date = db.Column(db.Date, nullable=False)
    duration_hours = db.Column(db.Float, nullable=False)
    pilot = db.Column(db.String(120))
    notes = db.Column(db.Text)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)


class Part(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    name = db.Column(db.String(150), nullable=False)
    serial_number = db.Column(db.String(100))
    resource_hours = db.Column(db.Float)
    resource_cycles = db.Column(db.Integer)
    status = db.Column(db.String(20), default="in_stock", nullable=False)

    drone_id = db.Column(db.Integer, db.ForeignKey("drone.id"), nullable=True)
    installed_date = db.Column(db.Date)
    drone_hours_at_install = db.Column(db.Float)
    drone_cycles_at_install = db.Column(db.Integer)
    hours_before_install = db.Column(db.Float, default=0.0)
    cycles_before_install = db.Column(db.Integer, default=0)

    notes = db.Column(db.Text)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)

    @property
    def status_label(self):
        return PART_STATUSES.get(self.status, self.status)

    @property
    def current_hours(self):
        base = self.hours_before_install or 0
        if self.status == "installed" and self.drone is not None and self.drone_hours_at_install is not None:
            delta = max(0.0, self.drone.flight_hours - self.drone_hours_at_install)
            return round(base + delta, 2)
        return round(base, 2)

    @property
    def current_cycles(self):
        base = self.cycles_before_install or 0
        if self.status == "installed" and self.drone is not None and self.drone_cycles_at_install is not None:
            delta = max(0, self.drone.flight_cycles - self.drone_cycles_at_install)
            return base + delta
        return base

    @property
    def remaining_hours(self):
        if self.resource_hours is None:
            return None
        return round(self.resource_hours - self.current_hours, 2)

    @property
    def remaining_cycles(self):
        if self.resource_cycles is None:
            return None
        return self.resource_cycles - self.current_cycles

    @property
    def resource_status(self):
        """ok / warning / overdue / unknown — худший из показателей по часам и циклам."""
        results = []

        if self.resource_hours:
            ratio = self.remaining_hours / self.resource_hours
            if ratio <= 0:
                results.append("overdue")
            elif ratio <= WARNING_RATIO:
                results.append("warning")
            else:
                results.append("ok")

        if self.resource_cycles:
            ratio = self.remaining_cycles / self.resource_cycles
            if ratio <= 0:
                results.append("overdue")
            elif ratio <= WARNING_RATIO:
                results.append("warning")
            else:
                results.append("ok")

        if not results:
            return "unknown"
        if "overdue" in results:
            return "overdue"
        if "warning" in results:
            return "warning"
        return "ok"


class ServiceRecord(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    drone_id = db.Column(db.Integer, db.ForeignKey("drone.id"), nullable=False)
    date = db.Column(db.Date, nullable=False)
    hours_at_event = db.Column(db.Float)
    category = db.Column(db.String(30), default="other", nullable=False)
    description = db.Column(db.Text)
    part_id = db.Column(db.Integer, db.ForeignKey("part.id"), nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)

    part = db.relationship("Part")

    @property
    def category_label(self):
        return SERVICE_CATEGORIES.get(self.category, self.category)
