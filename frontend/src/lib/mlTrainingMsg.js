/** The "NN.N%" accuracy a finished training run reports in its message, or null. */
export function accuracyFromMsg(msg) {
  const m = (msg || '').match(/(?<!\d)(\d+(?:\.\d*)?)\s*%/);
  return m ? m[1] : null;
}
