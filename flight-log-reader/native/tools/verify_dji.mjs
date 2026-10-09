// Сверка синтетических DJI-логов с эталонным парсером dji-log-parser-js (MIT)
// и запись ожидаемых значений для JUnit-тестов.
//   npm pack dji-log-parser-js && tar xzf dji-log-parser-js-*.tgz
//   node tools/verify_dji.mjs path/to/package/dji_log_parser_js.mjs
import { readFileSync, writeFileSync } from "fs";
import { dirname, join } from "path";
import { fileURLToPath } from "url";

const lib = await import(process.argv[2]);
const res = join(dirname(fileURLToPath(import.meta.url)), "..", "core", "src", "test", "resources");

function summarize(name, keychains) {
  const log = new lib.DJILog(readFileSync(join(res, name)));
  const records = log.records(keychains);
  const counts = {};
  for (const r of records) counts[r.type] = (counts[r.type] || 0) + 1;
  const osd = records.filter((r) => r.type === "OSD").map((r) => r.content);
  const bat = records.filter((r) => r.type === "CenterBattery").map((r) => r.content);
  const custom = records.filter((r) => r.type === "Custom").map((r) => r.content);
  const msgs = records
    .filter((r) => ["AppTip", "AppWarn", "AppSeriousWarn"].includes(r.type))
    .map((r) => r.type + ":" + r.content.message);
  const out = {
    version: log.version,
    productType: log.details.productType,
    aircraftName: log.details.aircraftName,
    aircraftSn: log.details.aircraftSn,
    startTime: log.details.startTime,
    totalDistance: log.details.totalDistance,
    counts,
    osdFirstLat: osd[0].latitude,
    osdFirstLon: osd[0].longitude,
    osdMaxAltitude: Math.max(...osd.map((o) => o.altitude)),
    osdModes: [...new Set(osd.map((o) => JSON.stringify(o.flightMode)))].map((s) => JSON.parse(s)),
    osdVibrating: osd.filter((o) => o.isVibrating).length,
    osdMotorUp: osd.filter((o) => o.isMotorUp).length,
    osdMinGps: Math.min(...osd.map((o) => o.gpsNum)),
    batteryMinVoltage: Math.min(...bat.map((b) => b.voltage)),
    batteryLastCurrent: bat[bat.length - 1].current,
    customFirst: custom[0].updateTimestamp,
    customLast: custom[custom.length - 1].updateTimestamp,
    messages: msgs,
  };
  if (log.version >= 13) out.keychainsRequest = log.keychainsRequest();
  return out;
}

const keychains = JSON.parse(readFileSync(join(res, "dji_v14.keychains.json"), "utf8"));
const result = { v12: summarize("dji_v12.txt"), v14: summarize("dji_v14.txt", keychains) };
writeFileSync(join(res, "dji_expected.json"), JSON.stringify(result, null, 1));
console.log(JSON.stringify(result, (k, v) => (k === "keychainsRequest" ? "[…]" : v), 1));
