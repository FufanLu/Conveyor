import type { Hit, SearchResult } from "../api/types";

// The same deterministic generator as catalog-svc's seed (V1_1__seed_products.sql), so the demo
// shows the products the real catalog holds: same SKUs, titles, brands, prices and ratings.
const BRANDS = ["Aldervale", "Brightwick", "Cobalt & Pine", "Dunmore", "Everline", "Fernhollow", "Gallant Goods",
  "Harborlight", "Ironbark", "Juniper Lane", "Kestrel", "Lumen & Co"];
const CATEGORIES: [string, string[]][] = [
  ["Electronics", ["Wireless Earbuds", "Smart Speaker", "USB-C Charger", "Portable SSD", "Webcam"]],
  ["Home & Kitchen", ["Chef Knife", "Cast Iron Skillet", "Pour-Over Kettle", "Air Fryer", "Blender"]],
  ["Books", ["Field Guide", "Cookbook", "Mystery Novel", "Atlas", "Sketchbook"]],
  ["Toys & Games", ["Puzzle", "Board Game", "Building Set", "Plush Fox", "Kite"]],
  ["Sports & Outdoors", ["Trail Backpack", "Yoga Mat", "Water Bottle", "Camping Lantern", "Bike Light"]],
  ["Beauty", ["Face Serum", "Hair Dryer", "Lip Balm", "Shampoo Bar", "Sunscreen"]],
  ["Grocery", ["Coffee Beans", "Green Tea", "Olive Oil", "Dark Chocolate", "Granola"]],
  ["Pet Supplies", ["Dog Leash", "Cat Tree", "Chew Toy", "Pet Bed", "Fish Food"]],
];
const ADJECTIVES = ["Classic", "Compact", "Deluxe", "Everyday", "Pro", "Ultra", "Travel", "Eco"];
const COUNT = 100_000;
const M = 4294967296;

const mul = (i: number, k: number) => Number((BigInt(i) * BigInt(k)) % BigInt(M));

function product(i: number): Hit {
  const h = mul(i, 2654435761);
  const hb = mul(i, 2246822519);
  const hc = mul(i, 3266489917);
  const hn = mul(i, 668265263);
  const ha = mul(i, 374761393);
  const hp = mul(i, 1103515245);
  const brand = BRANDS[Math.floor(hb / 11) % 12];
  const [category, nouns] = CATEGORIES[Math.floor(hc / 3) % 8];
  const adjective = ADJECTIVES[Math.floor(ha / 5) % 8];
  return {
    sku: "B0" + h.toString(16).toUpperCase().padStart(8, "0"),
    title: `${brand} ${adjective} ${nouns[Math.floor(hn / 7) % 5]} ${100 + (i % 900)}`,
    brand,
    category,
    priceCents: 199 + (Math.floor(hp / 13) % 19801),
    rating: Math.round((1 + (Math.floor(hp / 17) % 41) / 10) * 10) / 10,
  };
}

let all: Hit[] | null = null;
function catalog(): Hit[] {
  if (!all) all = Array.from({ length: COUNT }, (_, k) => product(k + 1));
  return all;
}

export function bySku(sku: string): Hit | undefined {
  return catalog().find((p) => p.sku === sku);
}

/** A crude stand-in for OpenSearch: every word must appear in the title or brand. */
export function search(text = "", category = "", page = 0, size = 20): SearchResult {
  const t0 = performance.now();
  const words = text.toLowerCase().split(/\s+/).filter(Boolean);
  const matched = catalog().filter((p) => {
    const hay = `${p.title} ${p.brand}`.toLowerCase();
    return words.every((w) => hay.includes(w)) && (!category || p.category === category);
  });
  const textOnly = category
    ? catalog().filter((p) => words.every((w) => `${p.title} ${p.brand}`.toLowerCase().includes(w)))
    : matched;
  const counts = new Map<string, number>();
  for (const p of textOnly) counts.set(p.category, (counts.get(p.category) ?? 0) + 1);
  const ranked = words.length ? matched : [...matched].sort((a, b) => b.rating - a.rating);
  return {
    total: matched.length,
    tookMillis: Math.max(1, Math.round(performance.now() - t0)),
    hits: ranked.slice(page * size, page * size + size),
    facets: { category: [...counts].map(([value, count]) => ({ value, count })).sort((a, b) => b.count - a.count) },
  };
}
