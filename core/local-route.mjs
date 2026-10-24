import { readFileSync } from 'node:fs';

// Sparse, offline example matching. These scores are similarities, not probabilities.
function features(text) {
  const words = text.normalize('NFKC').toLowerCase().match(/[a-z0-9]+|[\p{Script=Han}]+/gu) ?? [];
  const counts = new Map();
  for (const word of words) {
    const points = [...word];
    const parts = [];
    if (/^[a-z0-9]+$/.test(word) || points.length === 1) parts.push(word);
    else for (const size of [2, 3]) for (let index = 0; index + size <= points.length; index++) {
      parts.push(points.slice(index, index + size).join(''));
    }
    for (const part of parts) counts.set(part, (counts.get(part) ?? 0) + 1);
  }
  return counts;
}

export function createRouter({ routes, calibration }) {
  if (!routes.length || !calibration.length) throw new Error('本地分流需要示例和独立校准样本。');
  const documents = routes.flatMap(route => route.examples.map(text => ({ route: route.name, terms: features(text) })));
  const frequency = new Map();
  for (const { terms } of documents) for (const term of terms.keys()) frequency.set(term, (frequency.get(term) ?? 0) + 1);
  function encode(terms) {
    const vector = new Map();
    let norm = 0;
    for (const [term, count] of terms) {
      // Unknown terms still contribute to the norm: a greeting inside a long
      // unrelated task must not become a perfect match after dropping new words.
      const weight = (1 + Math.log(count)) * (1 + Math.log((documents.length + 1) / ((frequency.get(term) ?? 0) + 1)));
      vector.set(term, weight); norm += weight * weight;
    }
    const length = Math.sqrt(norm);
    if (length > 0) for (const [term, weight] of vector) vector.set(term, weight / length);
    return vector;
  }
  const examples = documents.map(document => ({ route: document.route, vector: encode(document.terms) }));
  function scores(text) {
    const vector = encode(features(text));
    return routes.map(route => ({ route: route.name, score: Math.max(0, ...examples.filter(example => example.route === route.name)
      .map(example => [...vector].reduce((sum, [term, weight]) => sum + weight * (example.vector.get(term) ?? 0), 0))) }));
  }
  const measured = calibration.map(sample => ({ ...sample, scores: scores(sample.text) }));
  const thresholds = new Map(routes.map(route => {
    const positives = measured.filter(sample => sample.route === route.name);
    if (!positives.length) throw new Error(`本地路线 ${route.name} 缺少正向校准样本。`);
    const negatives = measured.filter(sample => sample.route !== route.name);
    if (!negatives.length) throw new Error(`本地路线 ${route.name} 缺少负向校准样本。`);
    const threshold = Math.max(...negatives.map(sample => sample.scores.find(score => score.route === route.name).score)) + 1e-6;
    const supported = positives.some(sample => sample.scores.find(score => score.route === route.name).score >= threshold);
    return [route.name, supported ? threshold : Infinity];
  }));
  return {
    match(text) {
      const ranked = scores(text).sort((a, b) => b.score - a.score);
      const best = ranked[0];
      const threshold = thresholds.get(best.route);
      const tied = ranked.length > 1 && best.score - ranked[1].score < 0.05;
      return { route: !tied && best.score >= threshold ? best.route : null, score: best.score, threshold };
    },
  };
}

export const localRouter = createRouter(JSON.parse(readFileSync(new URL('./local-routes.json', import.meta.url), 'utf8')));
