package com.ctl.metaglasses.ui.home;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import android.content.Intent;

import com.ctl.metaglasses.databinding.FragmentHomeBinding;
import com.ctl.metaglasses.glasses.GlassesManager;
import com.ctl.metaglasses.sign.SignLanguagePipeline;
import com.ctl.metaglasses.ui.trial.TrialActivity;
import com.ctl.metaglasses.vision.BlurMode;

import java.util.Locale;
import java.util.Map;

public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;
    private GlassesManager glassesManager;
    private SignLanguagePipeline signPipeline;
    private TextToSpeech textToSpeech;
    private boolean textToSpeechReady = false;
    private final Handler alphabetHandler = new Handler(Looper.getMainLooper());
    private final String[] alphabet = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"
    };
    private int alphabetIndex = 0;
    private boolean alphabetPlaying = false;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionsResult);

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Create the manager and register the DAT camera-permission launcher here (before the
        // fragment is STARTED), as required by registerForActivityResult.
        signPipeline = new SignLanguagePipeline();
        glassesManager = new GlassesManager(requireContext());
        glassesManager.setLifecycleOwner(this);
        glassesManager.registerCameraPermission(this);
    }

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        HomeViewModel homeViewModel = new ViewModelProvider(this).get(HomeViewModel.class);

        binding = FragmentHomeBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        glassesManager.setMockDisplay(binding.mockGlassesDisplay);
        textToSpeech = new TextToSpeech(requireContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(Locale.US);
                textToSpeechReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });
        glassesManager.setListener(new GlassesManager.Listener() {
            @Override
            public void onStatusChanged(String status) {
                if (binding != null) {
                    binding.textStatus.setText("Status: " + status);
                }
            }

            @Override
            public void onFrameReceived(byte[] frameBytes, int width, int height, long timestampMillis) {
                String result = signPipeline.translateFrame(frameBytes, width, height, timestampMillis);
                glassesManager.sendTextToGlasses(result);
            }

            @Override
            public void onCameraFrame(android.graphics.Bitmap bitmap) {
                if (binding != null) {
                    binding.imageCameraPreview.setImageBitmap(bitmap);
                }
            }

            @Override
            public void onOriginalCameraFrame(android.graphics.Bitmap bitmap) {
                bitmap.recycle();
            }

            @Override
            public void onTranslationOutput(String text) {
                if (binding != null) {
                    binding.textTranslation.setText(text);
                }
            }

            @Override
            public void onError(String message, Throwable throwable) {
                if (binding != null) {
                    binding.textStatus.setText("Error: " + message);
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            }
        });

        homeViewModel.getText().observe(getViewLifecycleOwner(), binding.textHome::setText);

        binding.buttonConnect.setOnClickListener(v -> {
            if (hasRequiredPermissions()) {
                glassesManager.connect(requireActivity());
            } else {
                requestRequiredPermissions();
            }
        });

        binding.buttonPhoneTrial.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), TrialActivity.class)));

        binding.buttonMock.setOnClickListener(v -> {
            binding.textStatus.setText("Status: enabling mock glasses...");
            glassesManager.enableMockGlasses();
        });

        binding.buttonBlur.setOnClickListener(v -> {
            BlurMode next;
            switch (glassesManager.getBlurMode()) {
                case OFF: next = BlurMode.ALL_FACES; break;
                case ALL_FACES: next = BlurMode.MINORS_ONLY; break;
                default: next = BlurMode.OFF; break;
            }
            glassesManager.setBlurMode(next);
            switch (next) {
                case ALL_FACES:
                    binding.buttonBlur.setText("Face blur: ALL FACES");
                    break;
                case MINORS_ONLY:
                    binding.buttonBlur.setText("Face blur: MINORS");
                    if (!glassesManager.isAgeModelLoaded()) {
                        Toast.makeText(requireContext(),
                                "No age model loaded — blurring all faces (fail-safe)",
                                Toast.LENGTH_LONG).show();
                    }
                    break;
                default:
                    binding.buttonBlur.setText("Face blur: OFF");
                    break;
            }
        });

        binding.buttonStream.setOnClickListener(v -> {
            if (!glassesManager.isStreaming()) {
                if (!glassesManager.isConnected()) {
                    Toast.makeText(requireContext(), "Connect glasses first", Toast.LENGTH_SHORT).show();
                    return;
                }
                // Ask the glasses for camera access; the stream starts once it's granted.
                binding.buttonStream.setText("Stop camera stream");
                glassesManager.requestCameraPermission();
            } else {
                glassesManager.stopVideoStream();
                binding.buttonStream.setText("Start camera stream");
            }
        });

        binding.buttonDemoTranslate.setOnClickListener(v -> {
            String demoText = signPipeline.demoTranslation();
            binding.textTranslation.setText(demoText);
            glassesManager.sendTextToGlasses(demoText);
        });

        binding.buttonAlphabet.setOnClickListener(v -> {
            if (alphabetPlaying) {
                stopAlphabetDemo();
            } else {
                startAlphabetDemo();
            }
        });

        return root;
    }

    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRequiredPermissions() {
        permissionLauncher.launch(new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
        });
    }

    private void onPermissionsResult(Map<String, Boolean> results) {
        if (hasRequiredPermissions()) {
            glassesManager.connect(requireActivity());
        } else {
            binding.textStatus.setText("Status: camera/audio permissions required");
        }
    }

    private void startAlphabetDemo() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions();
            return;
        }

        if (!glassesManager.isConnected()) {
            glassesManager.connect(requireActivity());
        }

        alphabetIndex = 0;
        alphabetPlaying = true;
        binding.buttonAlphabet.setText("Stop alphabet demo");
        binding.textStatus.setText("Status: playing alphabet demo");
        runNextAlphabetLetter();
    }

    private void stopAlphabetDemo() {
        alphabetPlaying = false;
        alphabetHandler.removeCallbacksAndMessages(null);
        if (binding != null) {
            binding.buttonAlphabet.setText("Play alphabet");
            binding.textStatus.setText("Status: alphabet demo stopped");
        }
    }

    private void runNextAlphabetLetter() {
        if (!alphabetPlaying || binding == null) {
            return;
        }

        String current = alphabet[alphabetIndex];
        binding.textTranslation.setText(current);
        binding.textStatus.setText("Status: alphabet letter " + current);
        glassesManager.sendTextToGlasses(current);
        speakAlphabetLetter(current);

        alphabetIndex++;
        if (alphabetIndex >= alphabet.length) {
            alphabetPlaying = false;
            binding.buttonAlphabet.setText("Play alphabet");
            binding.textStatus.setText("Status: alphabet demo complete");
            return;
        }

        alphabetHandler.postDelayed(this::runNextAlphabetLetter, 1500);
    }

    private void speakAlphabetLetter(String letter) {
        if (textToSpeech != null && textToSpeechReady) {
            textToSpeech.speak(letter, TextToSpeech.QUEUE_FLUSH, null, "alphabet_letter");
        } else if (binding != null) {
            binding.textStatus.setText("Status: TTS not ready — showing letters only");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopAlphabetDemo();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        if (glassesManager != null) {
            glassesManager.disconnect();
        }
        binding = null;
    }
}
