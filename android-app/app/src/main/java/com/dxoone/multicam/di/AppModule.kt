package com.dxoone.multicam.di

import android.content.Context
import com.dxoone.multicam.usb.UsbDeviceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency injection module for application-wide dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideUsbDeviceManager(
        @ApplicationContext context: Context
    ): UsbDeviceManager {
        return UsbDeviceManager(context)
    }
}
