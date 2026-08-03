package com.yample.daily.controller

/** 各栏目 Fragment 统一刷新接口，DeviceControlActivity 收到快照后回调当前页面 */
interface SnapshotFragment {
    fun refresh(snapshot: DeviceSnapshot)
}
