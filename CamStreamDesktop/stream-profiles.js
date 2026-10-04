const PROFILES = Object.freeze({
  '720p30': Object.freeze({ id: '720p30', width: 1280, height: 720, fps: 30, bitrateKbps: 6000, maxBitrateKbps: 8000 }),
  '720p60': Object.freeze({ id: '720p60', width: 1280, height: 720, fps: 60, bitrateKbps: 10000, maxBitrateKbps: 14000 }),
  '1080p30': Object.freeze({ id: '1080p30', width: 1920, height: 1080, fps: 30, bitrateKbps: 12000, maxBitrateKbps: 16000 })
});
function getProfile(id = '720p30') {
  if (!Object.hasOwn(PROFILES, id)) throw new Error('Unsupported video profile');
  return PROFILES[id];
}
module.exports = { PROFILES, getProfile };
