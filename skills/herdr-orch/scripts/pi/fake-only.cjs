// Test-process egress guard. Load before Pi with NODE_OPTIONS, not from an extension:
// model/auth startup can precede extension loading. Only the fixture's exact origin is allowed.
const net = require("node:net");
const dns = require("node:dns");
const { syncBuiltinESMExports } = require("node:module");
const allowed = process.env.HERDR_ORCH_FAKE_ORIGIN;
if (allowed) {
  const url = new URL(allowed);
  if (url.protocol !== "http:" || url.hostname !== "127.0.0.1" || !url.port || url.origin !== allowed) {
    throw new Error("fake-only: expected an exact loopback HTTP origin");
  }
}
const refuse = () => { throw new Error("fake-only: outbound connection blocked"); };
const connect = net.Socket.prototype.connect;
net.Socket.prototype.connect = function (...args) {
  const arg = Array.isArray(args[0]) ? args[0][0] : args[0];
  const host = typeof arg === "object" ? arg.host : args[1];
  const port = typeof arg === "object" ? arg.port : arg;
  if (!allowed || host !== "127.0.0.1" || String(port) !== new URL(allowed).port) refuse();
  return connect.apply(this, args);
};
const lookup = dns.lookup;
dns.lookup = function (host, ...args) {
  if (host !== "127.0.0.1") refuse();
  return lookup.call(this, host, ...args);
};
const fetch = globalThis.fetch;
globalThis.fetch = function (input, init) {
  const url = new URL(typeof input === "string" || input instanceof URL ? input : input.url);
  if (!allowed || url.origin !== allowed) refuse();
  return fetch(input, { ...init, redirect: "error" });
};
syncBuiltinESMExports();
process.env.HERDR_ORCH_FAKE_GUARD_LOADED = "1";
