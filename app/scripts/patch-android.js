// Adds what VoiceAI needs to the generated Android project:
//  - microphone permission for voice input
//  - cleartext HTTP (the ESP32 speaks plain http on your LAN)
//  - <queries> so Android 11+ lets the app find the speech + TTS services
// Safe to run more than once.
const fs = require('fs');
const path = require('path');

const file = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'AndroidManifest.xml');
let xml = fs.readFileSync(file, 'utf8');

const perms = ['android.permission.RECORD_AUDIO', 'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE'];
for (const p of perms) {
  if (!xml.includes(`"${p}"`)) xml = xml.replace('</manifest>', `    <uses-permission android:name="${p}" />\n</manifest>`);
}

if (!xml.includes('android.speech.RecognitionService')) {
  xml = xml.replace('</manifest>', `    <queries>
        <intent><action android:name="android.speech.RecognitionService" /></intent>
        <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
    </queries>
</manifest>`);
}

if (!xml.includes('usesCleartextTraffic')) {
  xml = xml.replace('<application', '<application\n        android:usesCleartextTraffic="true"');
}

fs.writeFileSync(file, xml);
console.log('AndroidManifest.xml patched for VoiceAI');
