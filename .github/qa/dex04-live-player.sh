#!/usr/bin/env bash
set -euo pipefail
root="$1"
mkdir -p "$root/res/raw" "$root/src/qa/dex04/playback" "$root/classes" "$root/gen" "$root/dex"
engine="$(command -v espeak-ng || command -v espeak)"
"$engine" -v en-us -s 132 -w "$root/voice.wav" "Hello world. The weather is sunny today. This is a language translation test."
python3 - "$root/voice.wav" "$root/res/raw/fixture.wav" <<'PYTHON'
import sys
import wave

with wave.open(sys.argv[1], "rb") as source, wave.open(sys.argv[2], "wb") as fixture:
    fixture.setparams(source.getparams())
    fixture.writeframes(source.readframes(source.getnframes()))
    fixture.writeframes(bytes(3 * source.getframerate() * source.getsampwidth() * source.getnchannels()))
PYTHON
cat > "$root/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="qa.dex04.playback">
  <application android:label="DEX-04 playback fixture" android:allowBackup="false" android:allowAudioPlaybackCapture="true">
    <activity android:name=".PlayerActivity" android:exported="true" />
  </application>
</manifest>
XML
cat > "$root/src/qa/dex04/playback/PlayerActivity.java" <<'JAVA'
package qa.dex04.playback;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

public final class PlayerActivity extends Activity {
    private MediaPlayer player;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView message = new TextView(this);
        message.setText("DEX-04 local synthetic speech fixture");
        message.setTextSize(26);
        setContentView(message);
        player = new MediaPlayer();
        try (AssetFileDescriptor clip = getResources().openRawResourceFd(R.raw.fixture)) {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                    .build());
            player.setDataSource(clip.getFileDescriptor(), clip.getStartOffset(), clip.getLength());
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception error) {
            throw new IllegalStateException("Local speech playback failed", error);
        }
    }

    @Override public void onDestroy() {
        if (player != null) player.release();
        super.onDestroy();
    }
}
JAVA
platform="$ANDROID_HOME/platforms/android-36/android.jar"
build_tools="$ANDROID_HOME/build-tools/36.0.0"
"$build_tools/aapt2" compile --dir "$root/res" -o "$root/res.zip"
"$build_tools/aapt2" link -I "$platform" --manifest "$root/AndroidManifest.xml" \
    --min-sdk-version 29 --target-sdk-version 30 --java "$root/gen" -0 wav -o "$root/unsigned.apk" "$root/res.zip"
find "$root/src" "$root/gen" -type f -name '*.java' -print0 | xargs -0 javac -classpath "$platform" -source 8 -target 8 -d "$root/classes"
find "$root/classes" -type f -name '*.class' -print0 | xargs -0 "$build_tools/d8" --lib "$platform" --min-api 29 --output "$root/dex"
zip -j "$root/unsigned.apk" "$root/dex/classes.dex"
"$build_tools/zipalign" -f 4 "$root/unsigned.apk" "$root/aligned.apk"
export DEX04_FIXTURE_PASSWORD="$(openssl rand -hex 24)"
keytool -genkeypair -keystore "$root/disposable.p12" -storetype PKCS12 -alias fixture \
    -storepass:env DEX04_FIXTURE_PASSWORD -keyalg RSA -keysize 2048 -validity 7 -dname 'CN=Standalone QA audio fixture'
"$build_tools/apksigner" sign --ks "$root/disposable.p12" --ks-key-alias fixture \
    --ks-pass env:DEX04_FIXTURE_PASSWORD --out "$root/playback.apk" "$root/aligned.apk"
