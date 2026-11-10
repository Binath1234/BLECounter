package ik.kdu.ac.mc.blecounter

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ik.kdu.ac.mc.blecounter.ui.theme.BLECounterTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    // A Set is used to automatically prevent duplicate MAC addresses, ensuring we only count unique devices
    private val uniqueDevices = mutableSetOf<String>()

    // Launcher to handle the result of the permission request dialogue
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, "Permissions are required to scan BLE devices", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Request necessary Bluetooth and Location permissions when the app starts
        requestRequiredPermissions()

        setContent {
            BLECounterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 2. Load the main Jetpack Compose User Interface
                    BLEScannerScreen(this)
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        // Android 12 (API 31) and above require different Bluetooth permissions compared to older versions
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        // Check which permissions are not yet granted
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        // Launch the permission request if any are missing
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    // Function to append the scan results to a CSV file in the app's internal storage
    fun saveResultsToCSV(place: String, mode: String, crowd: String, count: Int) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd,HH:mm:ss", Locale.getDefault())
        val currentDateTime = dateFormat.format(Date())

        // Format the data as a comma-separated row ending with a new line
        val csvRow = "$currentDateTime,$place,$mode,$crowd,$count\n"

        try {
            // Context.MODE_APPEND adds new data to the end of the file without overwriting existing data
            openFileOutput("results.csv", Context.MODE_APPEND).use { output ->
                output.write(csvRow.toByteArray())
            }
            Toast.makeText(this, "Saved to results.csv", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error saving file", Toast.LENGTH_SHORT).show()
        }
    }

    // Public method to allow the Compose UI to access and modify the unique devices set
    fun getDeviceSet(): MutableSet<String> {
        return uniqueDevices
    }
}

@Composable
fun BLEScannerScreen(activity: MainActivity) {
    val context = activity.applicationContext
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val scanner = bluetoothManager.adapter?.bluetoothLeScanner

    // State variables to hold user selections from the dropdown menus
    var selectedPlace by remember { mutableStateOf("Empty room") }
    var selectedMode by remember { mutableStateOf("Low power") }
    var selectedCrowd by remember { mutableStateOf("Low") }

    // State variables to manage the UI state during and after scans
    var isScanning by remember { mutableStateOf(false) }
    var buttonLocked by remember { mutableStateOf(false) }
    var timeLeft by remember { mutableStateOf(30) }
    var finalDeviceCount by remember { mutableStateOf(0) }
    var scanCount by remember { mutableStateOf(0) } // Tracks progress out of the 18 required scans

    // Options for the dropdown menus
    val places = listOf("Empty room", "Library", "Canteen")
    val modes = listOf("Low power", "Low latency")
    val crowds = listOf("Low", "Medium", "High")

    // The callback that triggers every time a BLE device is found
    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                // Add the device's MAC address to the set (duplicates are automatically ignored)
                result.device?.address?.let { address ->
                    activity.getDeviceSet().add(address)
                }
            }
        }
    }

    Column(
        modifier = Modifier.padding(16.dp).fillMaxSize(),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Text("BLE Device Counter (Group 15)", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        // Reusable Dropdown components for experiment variables
        DropdownSelector("Select Place", places, selectedPlace) { selectedPlace = it }
        DropdownSelector("Scan Mode", modes, selectedMode) { selectedMode = it }
        DropdownSelector("Crowd Level", crowds, selectedCrowd) { selectedCrowd = it }

        Spacer(modifier = Modifier.height(32.dp))

        // The Start Scan Button
        Button(
            onClick = {
                // Ensure Bluetooth is available and turned on
                if (scanner == null) {
                    Toast.makeText(activity, "Please turn on Bluetooth!", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                // Reset state for a fresh scan
                activity.getDeviceSet().clear()
                isScanning = true
                buttonLocked = true
                timeLeft = 30
                finalDeviceCount = 0

                // Configure scan mode based on user selection
                val scanModeVal = if (selectedMode == "Low power") ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY
                val settings = ScanSettings.Builder().setScanMode(scanModeVal).build()

                // Start the BLE scan
                scanner.startScan(null, settings, scanCallback)

                // Timer 1: Runs for exactly 30 seconds to collect data
                object : CountDownTimer(30000, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        timeLeft = (millisUntilFinished / 1000).toInt()
                    }

                    override fun onFinish() {
                        // Stop scanning when 30 seconds are up
                        scanner.stopScan(scanCallback)
                        isScanning = false
                        finalDeviceCount = activity.getDeviceSet().size
                        scanCount += 1

                        // Save the results of this scan to the CSV file
                        activity.saveResultsToCSV(selectedPlace, selectedMode, selectedCrowd, finalDeviceCount)
                    }
                }.start()

                // Timer 2: Keeps the button locked for 35 seconds to respect Android's scan frequency limits
                object : CountDownTimer(35000, 1000) {
                    override fun onTick(millisUntilFinished: Long) {}
                    override fun onFinish() {
                        buttonLocked = false // Unlock the button so the next scan can begin
                    }
                }.start()

            },
            enabled = !buttonLocked, // Disable the button while scanning or in cooldown
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            // Update button text based on the current state
            Text(if (isScanning) "Scanning... $timeLeft s" else if (buttonLocked) "Wait (Saving...)" else "Start 30s Scan")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Display results dynamically
        if (isScanning) {
            Text("Live devices found: ${activity.getDeviceSet().size}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        } else if (finalDeviceCount > 0) {
            Text("Last Scan Result: $finalDeviceCount devices", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Total Scans Completed: $scanCount / 18", color = MaterialTheme.colorScheme.secondary)
    }
}

// A reusable Composable function to create Material 3 Dropdown Menus
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownSelector(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        // The visible text field showing the currently selected option
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            readOnly = true, // Prevent manual typing
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        // The list of options that appears when the box is clicked
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(selectionOption) },
                    onClick = {
                        onOptionSelected(selectionOption) // Pass the selected value back up
                        expanded = false // Close the menu
                    }
                )
            }
        }
    }
}