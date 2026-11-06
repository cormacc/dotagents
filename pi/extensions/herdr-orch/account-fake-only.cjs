// Test-only transport redirect: native OAuth URLs reach the bounded loopback fixture only.
require("../../../skills/herdr-orch/scripts/pi/fake-only.cjs");
globalThis.installAccountTestRedirect = () => {
  const fetch = globalThis.fetch;
  const origin = process.env.HERDR_ORCH_FAKE_ORIGIN;
  globalThis.fetch = (input, init) => {
    const url = new URL(typeof input === "string" || input instanceof URL ? input : input.url);
    if (url.href === "https://platform.claude.com/v1/oauth/token") {
      return fetch(origin + "/oauth", { ...init, redirect: "error" });
    }
    if (url.origin !== origin) throw new Error("account-fake-only: outbound fetch blocked");
    return fetch(input, { ...init, redirect: "error" });
  };
};
globalThis.installAccountTestRedirect();
