package com.github.k1rakishou.chan.core.di.module.application;

import com.github.k1rakishou.chan.core.cache.CacheHandler;
import com.github.k1rakishou.chan.core.cache.FileCacheV2;
import com.github.k1rakishou.chan.core.loader.impl.Chan4CloudFlareImagePreloader;
import com.github.k1rakishou.chan.core.loader.impl.PostExtraContentLoader;
import com.github.k1rakishou.chan.core.loader.impl.PrefetchLoader;
import com.github.k1rakishou.chan.core.loader.impl.external_media_service.ExternalMediaServiceExtraInfoFetcher;
import com.github.k1rakishou.chan.core.loader.impl.external_media_service.SoundCloudMediaServiceExtraInfoFetcher;
import com.github.k1rakishou.chan.core.loader.impl.external_media_service.StreamableMediaServiceExtraInfoFetcher;
import com.github.k1rakishou.chan.core.loader.impl.external_media_service.YoutubeMediaServiceExtraInfoFetcher;
import com.github.k1rakishou.chan.core.manager.Chan4CloudFlareImagePreloaderManager;
import com.github.k1rakishou.chan.core.manager.ChanThreadManager;
import com.github.k1rakishou.chan.core.manager.PrefetchStateManager;
import com.github.k1rakishou.chan.core.manager.ThreadDownloadManager;
import com.github.k1rakishou.core_logger.Logger;
import com.github.k1rakishou.model.repository.MediaServiceLinkExtraContentRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import io.reactivex.schedulers.Schedulers;

@Module
public class LoaderModule {

    @Provides
    @Singleton
    public PrefetchLoader providePrefetchLoader(
            Lazy<FileCacheV2> fileCacheV2,
            Lazy<CacheHandler> cacheHandler,
            PrefetchStateManager prefetchStateManager,
            Lazy<ChanThreadManager> chanThreadManager,
            Lazy<ThreadDownloadManager> threadDownloadManager,
            @Named(ExecutorsModule.onDemandContentLoaderExecutorName) Executor onDemandContentLoaderExecutor
    ) {
        Logger.deps("PrefetchLoader");

        return new PrefetchLoader(
                Schedulers.from(onDemandContentLoaderExecutor),
                fileCacheV2,
                cacheHandler,
                chanThreadManager,
                prefetchStateManager,
                threadDownloadManager
        );
    }

    @Provides
    @Singleton
    public Chan4CloudFlareImagePreloader provideChan4CloudFlareImagePreloader(
            Chan4CloudFlareImagePreloaderManager chan4CloudFlareImagePreloaderManager
    ) {
        Logger.deps("Chan4CloudFlareImagePreloader");

        return new Chan4CloudFlareImagePreloader(
                chan4CloudFlareImagePreloaderManager
        );
    }

    @Provides
    @Singleton
    public YoutubeMediaServiceExtraInfoFetcher provideYoutubeMediaServiceExtraInfoFetcher(
            MediaServiceLinkExtraContentRepository mediaServiceLinkExtraContentRepository
    ) {
        Logger.deps("YoutubeMediaServiceExtraInfoFetcher");

        return new YoutubeMediaServiceExtraInfoFetcher(mediaServiceLinkExtraContentRepository);
    }

    @Provides
    @Singleton
    public SoundCloudMediaServiceExtraInfoFetcher provideSoundCloudMediaServiceExtraInfoFetcher(
            MediaServiceLinkExtraContentRepository mediaServiceLinkExtraContentRepository
    ) {
        Logger.deps("SoundCloudMediaServiceExtraInfoFetcher");

        return new SoundCloudMediaServiceExtraInfoFetcher(mediaServiceLinkExtraContentRepository);
    }

    @Provides
    @Singleton
    public StreamableMediaServiceExtraInfoFetcher provideStreamableMediaServiceExtraInfoFetcher(
            MediaServiceLinkExtraContentRepository mediaServiceLinkExtraContentRepository
    ) {
        Logger.deps("StreamableMediaServiceExtraInfoFetcher");

        return new StreamableMediaServiceExtraInfoFetcher(mediaServiceLinkExtraContentRepository);
    }

    @Provides
    @Singleton
    public PostExtraContentLoader providePostExtraContentLoader(
            YoutubeMediaServiceExtraInfoFetcher youtubeMediaServiceExtraInfoFetcher,
            SoundCloudMediaServiceExtraInfoFetcher soundCloudMediaServiceExtraInfoFetcher,
            StreamableMediaServiceExtraInfoFetcher streamableMediaServiceExtraInfoFetcher,
            ChanThreadManager chanThreadManager,
            @Named(ExecutorsModule.onDemandContentLoaderExecutorName) Executor onDemandContentLoaderExecutor
    ) {
        Logger.deps("PostExtraContentLoader");

        List<ExternalMediaServiceExtraInfoFetcher> fetchers = new ArrayList<>();
        fetchers.add(youtubeMediaServiceExtraInfoFetcher);
        fetchers.add(soundCloudMediaServiceExtraInfoFetcher);
        fetchers.add(streamableMediaServiceExtraInfoFetcher);

        return new PostExtraContentLoader(
                Schedulers.from(onDemandContentLoaderExecutor),
                chanThreadManager,
                fetchers
        );
    }

}
