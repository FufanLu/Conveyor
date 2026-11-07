import type { Region } from "./types";

/** Fulfillment centers, ids and regions as seeded in inventory-svc; coordinates as the courier simulator uses them. */
export const FCS = [
  { id: "east-1", city: "Newark", region: "us-east", lat: 40.7357, lon: -74.1724 },
  { id: "east-2", city: "Baltimore", region: "us-east", lat: 39.2904, lon: -76.6122 },
  { id: "central-1", city: "Joliet", region: "us-central", lat: 41.525, lon: -88.0817 },
  { id: "south-1", city: "Dallas", region: "us-south", lat: 32.7767, lon: -96.797 },
  { id: "west-1", city: "Tracy", region: "us-west", lat: 37.7397, lon: -121.4252 },
  { id: "west-2", city: "Kent", region: "us-west", lat: 47.3809, lon: -122.2348 },
] as const satisfies readonly { id: string; city: string; region: Region; lat: number; lon: number }[];

export type FcId = (typeof FCS)[number]["id"];

export function regionOfFc(fc: string): Region {
  return `us-${fc.split("-")[0]}` as Region;
}

/** The FC nearest to a point: where a courier's route started. */
export function nearestFc(lat: number, lon: number) {
  let best: (typeof FCS)[number] = FCS[0];
  let bestD = Infinity;
  for (const fc of FCS) {
    const d = (fc.lat - lat) ** 2 + ((fc.lon - lon) * Math.cos((lat * Math.PI) / 180)) ** 2;
    if (d < bestD) {
      bestD = d;
      best = fc;
    }
  }
  return best;
}

/** Shipment ids are order ids; the simulator's synthetic ones start with SIM. Region by nearest FC. */
export function regionAt(lat: number, lon: number): Region {
  return nearestFc(lat, lon).region;
}

export function km(a: { lat: number; lon: number }, b: { lat: number; lon: number }): number {
  const r = 6371;
  const dLat = ((b.lat - a.lat) * Math.PI) / 180;
  const dLon = ((b.lon - a.lon) * Math.PI) / 180;
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((a.lat * Math.PI) / 180) * Math.cos((b.lat * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
  return 2 * r * Math.asin(Math.sqrt(h));
}
