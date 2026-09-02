export const constants = {
  Z_BEST_COMPRESSION: 9,
  Z_BEST_SPEED: 1,
  Z_DEFAULT_COMPRESSION: -1,
};

function unavailable() {
  throw new Error("compression commands are unavailable in mobile Bash mode");
}

export const gunzipSync = unavailable;
export const gzipSync = unavailable;
