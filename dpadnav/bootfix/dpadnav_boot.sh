#!/system/bin/sh
# Re-enable the Dpad Nav accessibility service after a cold boot. This TV clears
# enabled_accessibility_services on boot and can leave the app force-stopped (so the
# app's own BootReceiver never runs). This root init service fixes it reliably.
sleep 8
S="com.hisilicon.rmservice/.HiRMService:com.cleanhome.dpadnav/com.cleanhome.dpadnav.DpadService"
settings put secure enabled_accessibility_services "$S"
settings put secure accessibility_enabled 1
