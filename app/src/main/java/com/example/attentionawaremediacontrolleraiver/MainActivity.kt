package com.example.attentionawaremediacontrolleraiver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.example.attentionawaremediacontrolleraiver.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var player: ExoPlayer? = null

    // Dedicated single thread for ImageAnalysis — keeps analysis off the main thread
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // ---- Permission handling ----

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
    }

    // ---- Lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayer()
        checkCameraPermission()
        observeViewModel()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        cameraExecutor.shutdown()
    }

    // ---- Player ----

    @OptIn(UnstableApi::class)  // RawResourceDataSource is marked @UnstableApi
    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer

            // Load the video from res/raw/sample_video.mp4
            val uri = RawResourceDataSource.buildRawResourceUri(R.raw.sample_video)
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.repeatMode = Player.REPEAT_MODE_ONE  // loop forever
            exoPlayer.prepare()
            exoPlayer.play()  // start playing; ViewModel will pause if no face found
        }
    }

    // ---- Camera ----

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            // Use cases
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            val imageAnalysis = ImageAnalysis.Builder()
                // Drop frames we can't keep up with — never block the camera pipeline
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor, FaceAnalyzer { facePresent, latencyMs ->
                        viewModel.onFaceDetected(facePresent, latencyMs)
                    })
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera use-case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- ViewModel observer ----

    private fun observeViewModel() {
        lifecycleScope.launch {
            // repeatOnLifecycle cancels collection when the app goes to background
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateFaceStatusText(state)
                    updatePlayerStatus(state)
                    controlPlayback(state.shouldPlay)
                }
            }
        }
    }

    private fun updateFaceStatusText(state: UiState) {
        binding.tvFaceStatus.text = if (state.facePresent) {
            "Face detected  •  ${state.latencyMs} ms"
        } else {
            "No face detected"
        }
    }

    private fun updatePlayerStatus(state: UiState) {
        binding.tvPlayerStatus.text = if (state.shouldPlay) "Playing" else "Paused"
    }

    private fun controlPlayback(shouldPlay: Boolean) {
        val exoPlayer = player ?: return
        if (shouldPlay) exoPlayer.play() else exoPlayer.pause()
    }
}
