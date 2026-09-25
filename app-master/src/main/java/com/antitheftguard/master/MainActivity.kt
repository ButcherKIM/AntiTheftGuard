package com.antitheftguard.master

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.antitheftguard.master.databinding.ActivityMainBinding

/**
 * 마스터 앱 대시보드 화면입니다.
 * 기기 목록 확인, 지도 보기, 스트리밍 시작 및 QR 페어링 기능을 관리합니다.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupViews()
    }
    
    private fun setupViews() {
        binding.btnGoogleSignIn.setOnClickListener {
            // Google Sign-In 플로우 실행
        }
        
        binding.btnAddDevice.setOnClickListener {
            // QR 코드 생성 및 페어링 토큰 연동 로직
        }
    }
}
