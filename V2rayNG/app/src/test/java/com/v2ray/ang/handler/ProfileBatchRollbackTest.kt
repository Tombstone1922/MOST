package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.*

class ProfileBatchRollbackTest {
    @Test fun failedPayloadIndexAndSelectionWritesRestoreTheEntireBatch() {
        val values=mutableMapOf<String,MutableMap<String,String>>()
        val stores=mutableMapOf<String,MMKV>()
        var failStore="";var failKey="";var armed=false;var permanent=false
        fun store(id: String): MMKV = stores.getOrPut(id) {
            val rows=values.getOrPut(id) { mutableMapOf() }
            // Subclass mocking overrides MMKV's native lock/unlock/removal methods on the JVM.
            val mmkv=Mockito.mock(MMKV::class.java,Mockito.withSettings().mockMaker("mock-maker-subclass"))
            whenever(mmkv.decodeString(any())).thenAnswer { rows[it.getArgument<String>(0)] }
            whenever(mmkv.allKeys()).thenAnswer { rows.keys.toTypedArray() }
            whenever(mmkv.encode(any<String>(),any<String>())).thenAnswer {
                val key=it.getArgument<String>(0)
                if ((armed || permanent) && id==failStore && key==failKey) { armed=false;false }
                else { rows[key]=it.getArgument(1);true }
            }
            doAnswer { rows.remove(it.getArgument<String>(0));null }.whenever(mmkv).removeValueForKey(any())
            doAnswer {
                it.getArgument<Array<String>>(0).forEach(rows::remove);null
            }.whenever(mmkv).removeValuesForKeys(any())
            mmkv
        }
        MmkvTestStorage(mapOf("mainStorage" to store("MAIN"), "profileFullStorage" to store("PROFILE_FULL_CONFIG"),
            "serverRawStorage" to store("SERVER_RAW"), "serverAffStorage" to store("SERVER_AFF"),
            "settingsStorage" to store("SETTING"))).use {
            val profile=ProfileItem.create(EConfigType.VLESS).apply { server="entry.example.test";serverPort="443";password="id" }
            val index="SUB_SERVERS_${AppConfig.DEFAULT_SUBSCRIPTION_ID}"
            for ((id,key) in listOf("PROFILE_FULL_CONFIG" to "new-b", "SERVER_RAW" to "new-b", "MAIN" to index, "MAIN" to "SELECTED_SERVER")) {
                values.values.forEach { it.clear() }
                store("MAIN");store("PROFILE_FULL_CONFIG");store("SERVER_RAW");store("SERVER_AFF")
                values.getValue("MAIN")[index]=JsonUtil.toJson(listOf("old-guid"))
                values.getValue("PROFILE_FULL_CONFIG")["old-guid"]=JsonUtil.toJson(profile)
                val before=values.mapValues { it.value.toMap() }
                failStore=id;failKey=key;armed=true
                assertThrows(ProfileStorageException::class.java) {
                    MmkvManager.saveServerProfiles(linkedMapOf("new-a" to profile.copy(),"new-b" to profile.copy()),
                        mapOf("new-a" to "raw-a","new-b" to "raw-b"),AppConfig.DEFAULT_SUBSCRIPTION_ID,true)
                }
                assertEquals(before,values.mapValues { it.value.toMap() },"failed at $id/$key")
                assertFalse(armed,"failure path must actually execute")
            }
            MmkvManager.saveServerProfiles(linkedMapOf("new-b" to profile.copy(),"new-a" to profile.copy()),
                emptyMap(),AppConfig.DEFAULT_SUBSCRIPTION_ID,true,preferredSelection="new-a")
            assertEquals(listOf("new-a","new-b","old-guid"),MmkvManager.decodeServerList(AppConfig.DEFAULT_SUBSCRIPTION_ID))
            assertEquals("new-a",MmkvManager.getSelectServer())
            failStore="MAIN";failKey=index;permanent=true
            Mockito.mockStatic(android.util.Log::class.java).use {
                val error=assertThrows(ProfileStorageException::class.java) {
                    MmkvManager.saveServerProfiles(mapOf("new-a" to profile.copy()),emptyMap(),AppConfig.DEFAULT_SUBSCRIPTION_ID,true)
                }
                assertTrue(error.message.orEmpty().contains("recovery could not be confirmed"))
                assertEquals(2,error.suppressed.size)
            }
        }
    }
}
