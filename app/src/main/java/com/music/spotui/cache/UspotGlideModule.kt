package com.music.spotui.cache

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.module.GlideModule

/** Manifest registration keeps the existing Glide cache and evicts it using Glide's own journal. */
@Suppress("DEPRECATION")
class UspotGlideModule : GlideModule {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, CacheBudget.ARTWORK_BYTES))
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) = Unit
}
