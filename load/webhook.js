import crypto from "k6/crypto";
import http from "k6/http";
import { check } from "k6";

const baseUrl = __ENV.BASE_URL || "http://localhost:8080";
const secret = __ENV.SHOPIFY_WEBHOOK_SECRET || "development-secret";

export const options = {
  scenarios: {
    webhooks: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RATE || 100),
      timeUnit: "1s",
      duration: __ENV.DURATION || "30s",
      preAllocatedVUs: Number(__ENV.VUS || 40),
      maxVUs: Number(__ENV.MAX_VUS || 100),
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(99)<250"],
  },
};

export function setup() {
  const runId = String(Date.now());
  const headers = { "Content-Type": "application/json" };
  const sku = http.post(`${baseUrl}/api/identity/skus`, JSON.stringify({
    sku: `LOAD-${runId}`, style: "LOAD", color: "CRIMSON", size: "ONE", skuClass: "LOAD",
  }), { headers }).json();
  const location = http.post(`${baseUrl}/api/identity/locations`, JSON.stringify({
    code: `LOAD-${runId}`, name: "Load test", type: "WAREHOUSE",
  }), { headers }).json();
  http.post(`${baseUrl}/api/identity/sku-mappings`, JSON.stringify({
    canonicalSkuId: sku.id, system: "shopify", externalId: runId,
  }), { headers });
  http.post(`${baseUrl}/api/identity/location-mappings`, JSON.stringify({
    locationId: location.id, system: "shopify", externalId: runId,
  }), { headers });
  return { runId };
}

export default function (data) {
  const body = JSON.stringify({
    inventory_item_id: Number(data.runId),
    location_id: Number(data.runId),
    available: 100,
    updated_at: new Date().toISOString(),
  });
  const signature = crypto.hmac("sha256", secret, body, "base64");
  const response = http.post(`${baseUrl}/webhooks/shopify`, body, {
    headers: {
      "Content-Type": "application/json",
      "X-Shopify-Hmac-Sha256": signature,
      "X-Shopify-Webhook-Id": `${data.runId}-${__VU}-${__ITER}-${Date.now()}`,
      "X-Shopify-Topic": "inventory_levels/update",
    },
  });
  check(response, { "webhook acknowledged": (result) => result.status === 200 });
}

