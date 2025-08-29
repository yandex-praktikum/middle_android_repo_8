package ru.yandexpraktikum.blechat.data.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.yandexpraktikum.blechat.domain.bluetooth.BleClientController
import ru.yandexpraktikum.blechat.domain.model.Message
import ru.yandexpraktikum.blechat.domain.model.ScannedBluetoothDevice
import ru.yandexpraktikum.blechat.presentation.notifications.NotificationsHelper
import ru.yandexpraktikum.blechat.utils.checkForConnectPermission
import ru.yandexpraktikum.blechat.utils.notifyCharUUID
import ru.yandexpraktikum.blechat.utils.serviceUUID
import ru.yandexpraktikum.blechat.utils.writeCharUUID
import java.nio.charset.Charset
import ru.yandexpraktikum.blechat.R
import javax.inject.Inject

class BleClientControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val locationManager: LocationManager,
    private val viewModelScope: CoroutineScope,
    private val notificationsHelper: NotificationsHelper
): BleClientController {

    private var currentGatt: BluetoothGatt? = null

    val gattCallback = getGattCallback()

    private val bleScanner by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    private val _isBluetoothEnabled = MutableStateFlow(false)
    override val isBluetoothEnabled: StateFlow<Boolean>
        get() = _isBluetoothEnabled.asStateFlow()

    private val _isLocationEnabled = MutableStateFlow(false)
    override val isLocationEnabled: StateFlow<Boolean>
        get() = _isLocationEnabled.asStateFlow()


    init {
        updateBluetoothState()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            updateLocationState()
        }
    }

    override fun updateBluetoothState() {
        try {
            _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
        } catch (e: Exception) {
            Log.e("BLE", "Failed to initialize Bluetooth state", e)
        }
    }

    override fun updateLocationState() {
        try {
            _isLocationEnabled.value = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            Log.e("BLE", "Failed to initialize Location state", e)
        }
    }

    private val _scannedDevices = MutableStateFlow<List<ScannedBluetoothDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedBluetoothDevice>>
        get() = _scannedDevices.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            context.checkForConnectPermission {
                val bluetoothDevice = ScannedBluetoothDevice(
                    name = device.name,
                    address = device.address
                )
                _scannedDevices.update { devices ->
                    if (devices.none { it.address == bluetoothDevice.address }) {
                        devices + bluetoothDevice
                    } else devices
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BLE", "Scan failed with error code: $errorCode")
        }
    }

    override fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bleScanner?.startScan(scanCallback)
    }

    override fun stopScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_ADMIN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bleScanner?.stopScan(scanCallback)
        _scannedDevices.update {
            it.filter { device ->
                device.isConnected
            }
        }
    }

    override fun connectToDevice(device: ScannedBluetoothDevice): Boolean {
        val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
        context.checkForConnectPermission {
            currentGatt = bluetoothDevice?.connectGatt(context, false, gattCallback)
        }
        return currentGatt != null
    }

    override suspend fun sendMessage(message: String, deviceAddress: String): Boolean {
        val gattService = currentGatt?.getService(serviceUUID)
        val characteristic = gattService?.getCharacteristic(writeCharUUID)

        return if (characteristic != null) {
            characteristic.setValue(message.toByteArray(Charset.defaultCharset()))
            context.checkForConnectPermission {
                currentGatt?.writeCharacteristic(characteristic)
            }
            addMessage(
                deviceAddress = deviceAddress,
                message = Message(
                    text = message,
                    senderAddress = bluetoothAdapter?.address ?: "",
                    isFromLocalUser = true
                )
            )
         true
        } else false
    }

    private fun getGattCallback() : BluetoothGattCallback {
        return object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    when(newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            context.checkForConnectPermission {
                                gatt.discoverServices()
                            }
                            updateState(
                                deviceAddress = gatt.device.address,
                                true
                            )
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            updateState(
                                deviceAddress = gatt.device.address,
                                false
                            )
                            closeConnection()
                        }
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt?.getService(serviceUUID)
                    val notifyCharacteristic = service?.getCharacteristic(notifyCharUUID)
                    if (notifyCharacteristic != null) {
                        context.checkForConnectPermission {
                            gatt.setCharacteristicNotification(notifyCharacteristic, true)
                        }
                    }
                }

            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid == notifyCharUUID) {
                    val message = String(characteristic.value, Charset.defaultCharset())

                    notificationsHelper.notifyOnMessageReceived(
                        title = context.getString(R.string.new_message),
                        message = message
                    )

                    viewModelScope.launch {
                        addMessage(
                            deviceAddress = gatt.device.address,
                            message = Message(
                                text = message,
                                senderAddress = gatt.device.address,
                                isFromLocalUser = false
                            )
                        )
                    }
                }
            }
        }
    }

    private fun updateState(deviceAddress: String, isConnected: Boolean) {
        _scannedDevices.update { devices ->
            devices.map { device ->
                if (device.address == deviceAddress) {
                    device.copy(isConnected = isConnected)
                } else {
                    device
                }
            }
        }
    }

    private fun addMessage(deviceAddress: String, message: Message) {
        _scannedDevices.update { devices ->
            devices.map { device ->
                if (device.address == deviceAddress) {
                    device.copy(messages = device.messages + message)
                } else {
                    device
                }
            }
        }
    }

    override fun closeConnection() {
        context.checkForConnectPermission {
            currentGatt?.close()
        }
        currentGatt = null
    }

    override fun release() {
        closeConnection()
    }
}