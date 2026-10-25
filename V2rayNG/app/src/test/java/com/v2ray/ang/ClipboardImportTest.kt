package com.v2ray.ang

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.v2ray.ang.util.Utils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class ClipboardImportTest {
    @Test fun emptyClipboardIsAnEmptyString() {
        val context = mock<Context>();val clipboard = mock<ClipboardManager>()
        whenever(context.getSystemService(Context.CLIPBOARD_SERVICE)).thenReturn(clipboard)
        assertEquals("",Utils.getClipboard(context))
    }
    @Test fun allClipboardItemsAreRead() {
        val context = mock<Context>();val clipboard = mock<ClipboardManager>();val clip = mock<ClipData>()
        whenever(context.getSystemService(Context.CLIPBOARD_SERVICE)).thenReturn(clipboard)
        whenever(clipboard.primaryClip).thenReturn(clip);whenever(clip.itemCount).thenReturn(3)
        for (i in 0..2) {
            val item = mock<ClipData.Item>();whenever(item.text).thenReturn("link-$i")
            whenever(clip.getItemAt(i)).thenReturn(item)
        }
        assertEquals("link-0\nlink-1\nlink-2",Utils.getClipboard(context))
    }
}
