# Room
-keep class * extends androidx.room.RoomDatabase
-keep class com.yample.daily.controller.DeviceRecord { *; }

# 协议类防混淆（Gson 序列化依赖字段名）
-keep class com.yample.daily.controller.MqttPacket { *; }
-keep class com.yample.daily.controller.PacketValue { *; }
-keep class com.yample.daily.controller.PacketValue$* { *; }
# 绑定二维码载荷（Gson 反序列化依赖字段名）
-keep class com.yample.daily.controller.BindingPayload { *; }
# 配对握手派生会话密钥
-keep class com.yample.daily.controller.Hkdf { *; }

# Paho MQTT client：内部通过反射（Class.forName）加载 SimpleLogger 等日志类，
# 混淆后会抛 MissingResourceException「Error locating the logging class」导致崩溃
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
