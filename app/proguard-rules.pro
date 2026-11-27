# Sherpa-ONNX 1.13.8 ships an empty proguard.txt. These JNI functions use
# GetFieldID with hard-coded field names/descriptors in the config objects.
# Keep the fields read by these entry points while allowing unrelated APIs to shrink.

# offline-recognizer.cc reads the default sub-configs as well as the selected model.
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineRecognizerConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.FeatureConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.HomophoneReplacerConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineCohereTranscribeModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineMedAsrCtcModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.QnnConfig { <fields>; }

# OfflineRecognizer.getResult constructs the result directly in JNI.
-keep class com.k2fsa.sherpa.onnx.OfflineRecognizerResult {
    <init>(java.lang.String, java.lang.String[], float[], java.lang.String, java.lang.String, java.lang.String, float[]);
}

# voice-activity-detector.cc and spoken-language-identification.cc.
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.VadModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.SileroVadModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.TenVadModelConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig { <fields>; }
-keepclassmembers,includedescriptorclasses class com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig { <fields>; }
