package com.example.ocrtextapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@ExperimentalGetImage
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var captureButton: ImageButton
    private lateinit var bottomSheet: View
    private lateinit var extractedTextView: TextView
    private lateinit var saveToAllergyBtn: Button
    private lateinit var manualInputBtn: Button
    private lateinit var switchModeButton: ImageButton
    private lateinit var modeLabel: TextView
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private var isBarcodeMode = false

    private val userAllergens = listOf(
        "milk", "eggs", "fish", "shellfish", "tree nuts", "peanuts", "wheat", "soy", "sesame",
        "gluten", "mustard", "celery", "lupin", "sulfites", "dairy", "corn", "yeast", "mollusks",
        "nightshades", "legumes", "citrus", "tomatoes", "strawberries", "chocolate", "caffeine",
        "food dyes", "alcohol", "gelatin", "msg"
    )

    private val mockProductDB = mapOf(
        "6294001819226" to "Sugar, milk, cocoa, caffeine, nuts.",
        "987654321098" to "Milk, cultures, fruit.",
        "555555555555" to "Wheat, yeast, salt, sugar.",
        "111111111111" to "Water, orange, sugar.",
        "222222222222" to "Caffeine, sugar, flavoring."
    )

    @SuppressLint("ClickableViewAccessibility", "ResourceType")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        rootLayout = findViewById(R.id.main)
        captureButton = findViewById(R.id.captureButton)
        bottomSheet = findViewById(R.id.bottomSheet)
        extractedTextView = findViewById(R.id.extractedTextView)
        saveToAllergyBtn = findViewById(R.id.saveToAllergyBtn)
        manualInputBtn = findViewById(R.id.manualInputBtn)
        switchModeButton = findViewById(R.id.switchmodebutton)
        modeLabel = findViewById(R.id.textView)


        cameraExecutor = Executors.newSingleThreadExecutor()

        requestPermissionAndStartCamera()

        captureButton.setOnClickListener {
            captureButton.isEnabled = false

            captureAndAnalyzeImage()

            captureButton.postDelayed({
                captureButton.isEnabled = true
            }, 1000)
        }

        switchModeButton.setOnClickListener {
            isBarcodeMode = !isBarcodeMode
            showSnackbar("Switched to ${if (isBarcodeMode) "Barcode" else "Text"} mode.")

            val iconRes = if (isBarcodeMode) R.drawable.scan else R.drawable.note
            captureButton.setImageResource(iconRes)
        }

        saveToAllergyBtn.setOnClickListener {
            showSnackbar("Ingredients saved to allergy list!")
        }

        manualInputBtn.setOnClickListener {
            val dialogView = layoutInflater.inflate(R.layout.manualinput, null)
            val editText = dialogView.findViewById<EditText>(R.id.manualEditText)
            editText.setText(extractedTextView.text.toString())

            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle("Edit Scanned Text")
                .setView(dialogView)
                .setPositiveButton("Save") { _, _ ->
                    val editedText = editText.text.toString().trim()
                    val capitalizedText = capitalizeWords(editedText)
                    extractedTextView.text = highlightAllergens(capitalizedText)
                    showSnackbar("Text updated.")
                }
                .setNegativeButton("Cancel", null)
                .create()

            dialog.show()
        }

        rootLayout.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val location = IntArray(2)
                bottomSheet.getLocationOnScreen(location)
                val x = event.rawX
                val y = event.rawY

                if (x < location[0] || x > location[0] + bottomSheet.width ||
                    y < location[1] || y > location[1] + bottomSheet.height
                ) {
                    bottomSheet.visibility = View.GONE
                    v.performClick()
                }
            }
            true
        }
    }

    private fun requestPermissionAndStartCamera() {
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) startCamera()
            else showSnackbar("Camera permission is required.")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (e: Exception) {
                Log.e("Camera", "Failed to bind camera use cases: ${e.message}", e)
                showSnackbar("Failed to start camera.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndAnalyzeImage() {
        val capture = imageCapture ?: return
        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    imageProxy.image?.let { mediaImage ->
                        val image = InputImage.fromMediaImage(
                            mediaImage, imageProxy.imageInfo.rotationDegrees
                        )
                        if (isBarcodeMode) analyzeImageForBarcode(image)
                        else analyzeImageForText(image)
                    }
                    imageProxy.close()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("ImageCapture", "Capture failed: ${exception.message}", exception)
                    showSnackbar("Capture failed.")
                }
            }
        )
    }

    private fun analyzeImageForText(image: InputImage) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val extracted = visionText.text.trim()

                if (extracted.isEmpty()) {
                    extractedTextView.text = "No text was scanned."
                } else {
                    val formattedIngredients = formatIngredients(extracted)
                    val capitalizedText = capitalizeWords(formattedIngredients)
                    extractedTextView.text = highlightAllergens(capitalizedText)
                }

                bottomSheet.visibility = View.VISIBLE
            }
            .addOnFailureListener { e ->
                Log.e("TextRecognition", "Text recognition failed.", e)
                showSnackbar("Text recognition failed: ${e.message}")
            }
    }

    private fun analyzeImageForBarcode(image: InputImage) {
        val scanner = BarcodeScanning.getClient()
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isEmpty()) {
                    showSnackbar("No barcode found.")
                    return@addOnSuccessListener
                }

                val results = StringBuilder()

                for (barcode in barcodes) {
                    val code = barcode.rawValue ?: "Unknown"
                    val productInfo = mockProductDB[code]

                    if (productInfo != null) {
                        results.append(productInfo)
                    } else {
                        results.append("Product not found for barcode: $code")
                    }
                }

                val highlighted = highlightAllergens(results.toString())
                extractedTextView.text = highlighted
                bottomSheet.visibility = View.VISIBLE
            }
            .addOnFailureListener { e ->
                Log.e("BarcodeScanner", "Barcode scanning failed.", e)
                showSnackbar("Barcode scanning failed: ${e.message}")
            }
    }

    private fun formatIngredients(extractedText: String): String {
        return extractedText
            .split(",", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ") + "."
    }

    private fun capitalizeWords(text: String): String {
        return text.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }

    private fun highlightAllergens(text: String): SpannableString {
        val spannable = SpannableString(text)
        val lowerText = text.lowercase(Locale.getDefault())

        userAllergens.forEach { allergen ->
            var startIndex = lowerText.indexOf(allergen)
            while (startIndex >= 0) {
                spannable.setSpan(
                    ForegroundColorSpan(Color.RED),
                    startIndex,
                    startIndex + allergen.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                startIndex = lowerText.indexOf(allergen, startIndex + allergen.length)
            }
        }
        return spannable
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(rootLayout, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}