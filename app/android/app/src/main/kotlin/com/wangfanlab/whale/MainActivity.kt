package com.wangfanlab.whale

import com.wangfanlab.whale.transport.TransportBridge
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var transportBridge: TransportBridge? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        transportBridge = TransportBridge(this, flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        transportBridge?.dispose()
        transportBridge = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
