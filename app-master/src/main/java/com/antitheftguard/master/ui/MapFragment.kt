package com.antitheftguard.master.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.antitheftguard.master.R
import com.antitheftguard.master.databinding.FragmentMapBinding

/**
 * 마스터 앱에서 기기의 위치 이력과 현재 위치를 Google 지도에 표시하는 프래그먼트입니다.
 */
class MapFragment : Fragment() {
    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // TODO: Google Maps 로드 및 LocationRepository를 통한 위치 데이터 마커/폴리라인 표시
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
