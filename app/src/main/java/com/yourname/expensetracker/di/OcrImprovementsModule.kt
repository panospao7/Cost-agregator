package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.receipt.EnhancedMerchantExtractor
import com.yourname.expensetracker.domain.receipt.OcrLanguageProcessor
import com.yourname.expensetracker.domain.receipt.OcrPreprocessingPipeline
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OcrImprovementsModule {
    
    @Provides
    @Singleton
    fun provideEnhancedMerchantExtractor(extractor: EnhancedMerchantExtractor): EnhancedMerchantExtractor {
        return extractor
    }
    
    @Provides
    @Singleton
    fun provideOcrLanguageProcessor(processor: OcrLanguageProcessor): OcrLanguageProcessor {
        return processor
    }
    
    @Provides
    @Singleton
    fun provideOcrPreprocessingPipeline(pipeline: OcrPreprocessingPipeline): OcrPreprocessingPipeline {
        return pipeline
    }
}
