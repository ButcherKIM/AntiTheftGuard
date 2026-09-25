package com.antitheftguard.master.recorder

import android.content.Context
import java.io.File

/** WebRTC 스트림을 MP4 파일로 로컬에 녹화하는 기능 (더미) */
class StreamRecorder(private val context: Context) {
    
    private var isRecording = false
    private var outputFile: File? = null
    
    fun startRecording(streamId: String) {
        if (isRecording) return
        isRecording = true
        val dir = context.getExternalFilesDir(null)
        outputFile = File(dir, "record_${System.currentTimeMillis()}.mp4")
        // TODO: MediaRecorder / FFmpeg 등을 사용하여 스트림 데이터를 저장
    }
    
    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        // TODO: 녹화 종료 및 파일 저장 완료 처리
    }
}
