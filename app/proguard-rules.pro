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
