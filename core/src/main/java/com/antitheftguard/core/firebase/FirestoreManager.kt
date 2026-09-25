package com.antitheftguard.core.firebase

import com.antitheftguard.core.model.*
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import java.util.UUID

/** Firestore 데이터베이스와 통신하는 유틸리티 클래스 */
class FirestoreManager {
    private val db = Firebase.firestore
    
    suspend fun saveGpsBatch(deviceId: String, batch: GpsBatch): Result<Unit> = runCatching {
        db.collection("devices").document(deviceId)
          .collection("gps_batches").document(batch.startTime.toString())
          .set(batch).await()
    }
    
    suspend fun getLocationHistory(deviceId: String, fromTime: Long, toTime: Long): Result<List<GpsBatch>> = runCatching {
        val snapshot = db.collection("devices").document(deviceId)
            .collection("gps_batches")
            .whereGreaterThanOrEqualTo("startTime", fromTime)
            .whereLessThanOrEqualTo("endTime", toTime)
            .get().await()
        snapshot.documents.mapNotNull { it.toObject(GpsBatch::class.java) }
    }
    
    suspend fun deleteOldLocations(deviceId: String, olderThan: Long): Result<Int> = runCatching {
        val snapshot = db.collection("devices").document(deviceId)
            .collection("gps_batches")
            .whereLessThan("endTime", olderThan)
            .get().await()
        var count = 0
        db.runBatch { batch ->
            for (doc in snapshot.documents) {
                batch.delete(doc.reference)
                count++
            }
        }.await()
        count
    }
    
    suspend fun registerDevice(device: DeviceInfo): Result<Unit> = runCatching {
        db.collection("devices").document(device.deviceId).set(device).await()
    }
    
    suspend fun getDevice(deviceId: String): Result<DeviceInfo?> = runCatching {
        db.collection("devices").document(deviceId).get().await().toObject(DeviceInfo::class.java)
    }
    
    suspend fun updateFcmToken(deviceId: String, token: String): Result<Unit> = runCatching {
        db.collection("devices").document(deviceId).update("fcmToken", token).await()
    }
    
    suspend fun getUserDevices(userId: String): Result<List<DeviceInfo>> = runCatching {
        val snapshot = db.collection("devices").whereEqualTo("ownerId", userId).get().await()
        snapshot.documents.mapNotNull { it.toObject(DeviceInfo::class.java) }
    }
    
    suspend fun createSignalingRoom(deviceId: String): Result<String> = runCatching {
        val roomId = UUID.randomUUID().toString()
        db.collection("devices").document(deviceId).collection("signaling").document(roomId).set(mapOf("created" to true)).await()
        roomId
    }
    
    suspend fun setOffer(deviceId: String, roomId: String, sdp: String): Result<Unit> = runCatching {
        db.collection("devices").document(deviceId).collection("signaling").document(roomId).set(mapOf("offer" to sdp)).await()
    }
    
    suspend fun setAnswer(deviceId: String, roomId: String, sdp: String): Result<Unit> = runCatching {
        db.collection("devices").document(deviceId).collection("signaling").document(roomId).update("answer", sdp).await()
    }
    
    suspend fun addIceCandidate(deviceId: String, roomId: String, candidate: IceCandidateData): Result<Unit> = runCatching {
        db.collection("devices").document(deviceId).collection("signaling").document(roomId).collection("candidates").add(candidate).await()
        Unit
    }
    
    fun observeSignaling(deviceId: String, roomId: String): Flow<SignalingData> = callbackFlow {
        val listener = db.collection("devices").document(deviceId).collection("signaling").document(roomId).addSnapshotListener { snapshot, e ->
            if (e != null) { close(e); return@addSnapshotListener }
            if (snapshot != null && snapshot.exists()) {
                snapshot.getString("offer")?.let { trySend(SignalingData("offer", it)) }
                snapshot.getString("answer")?.let { trySend(SignalingData("answer", it)) }
            }
        }
        awaitClose { listener.remove() }
    }
    
    fun observeIceCandidates(deviceId: String, roomId: String): Flow<IceCandidateData> = callbackFlow {
        val listener = db.collection("devices").document(deviceId).collection("signaling").document(roomId).collection("candidates")
            .addSnapshotListener { snapshot, e ->
                if (e != null) { close(e); return@addSnapshotListener }
                snapshot?.documentChanges?.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        change.document.toObject(IceCandidateData::class.java).let { trySend(it) }
                    }
                }
            }
        awaitClose { listener.remove() }
    }
    
    suspend fun cleanupSignaling(deviceId: String, roomId: String): Result<Unit> = runCatching {
        db.collection("devices").document(deviceId).collection("signaling").document(roomId).delete().await()
    }
    
    suspend fun createPairingToken(userId: String): Result<PairingToken> = runCatching {
        val tokenStr = UUID.randomUUID().toString()
        val token = PairingToken(tokenStr, userId, System.currentTimeMillis(), System.currentTimeMillis() + 300000)
        db.collection("pairingTokens").document(tokenStr).set(token).await()
        token
    }
    
    suspend fun consumePairingToken(token: String): Result<PairingToken?> = runCatching {
        val doc = db.collection("pairingTokens").document(token).get().await()
        if (doc.exists()) {
            val pToken = doc.toObject(PairingToken::class.java)
            db.collection("pairingTokens").document(token).delete().await()
            pToken
        } else null
    }
}
