package net.sourceforge.subsonic.service;

import net.sourceforge.subsonic.Logger;
import net.sourceforge.subsonic.dao.PodcastDao;
import net.sourceforge.subsonic.domain.PodcastChannel;
import net.sourceforge.subsonic.domain.PodcastEpisode;
import net.sourceforge.subsonic.util.StringUtil;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.Namespace;
import org.jdom.input.SAXBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

/**
 * Provides services for Podcast reception.
 *
 * @author Sindre Mehus
 */
public class PodcastService {

    private static final Logger LOG = Logger.getLogger(PodcastService.class);
    private static final DateFormat RSS_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
    private static final Namespace ITUNES_NAMESPACE = Namespace.getNamespace("http://www.itunes.com/DTDs/Podcast-1.0.dtd");

    private final ExecutorService refreshExecutor;
    private final ExecutorService downloadExecutor;
    private final ScheduledExecutorService scheduledExecutor;
    private ScheduledFuture<?> scheduledRefresh;
    private PodcastDao podcastDao;
    private SettingsService settingsService;
    private SecurityService securityService;

    public PodcastService() {
        ThreadFactory threadFactory = new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = Executors.defaultThreadFactory().newThread(r);
                t.setDaemon(true);
                return t;
            }
        };
        refreshExecutor = Executors.newSingleThreadExecutor(threadFactory);
        downloadExecutor = Executors.newFixedThreadPool(3, threadFactory);
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    public synchronized void schedule() {
        Runnable task = new Runnable() {
            public void run() {
                refresh(true);
            }
        };

        if (scheduledRefresh != null) {
            scheduledRefresh.cancel(true);
        }

        int hoursBetween = settingsService.getPodcastUpdateInterval();

        if (hoursBetween == -1) {
            LOG.info("Automatic Podcast update disabled.");
            return;
        }

        long periodMillis = hoursBetween * 60L * 1000L;
        long initialDelayMillis = 5L * 60L * 1000L;

        scheduledRefresh = scheduledExecutor.scheduleAtFixedRate(task, initialDelayMillis, periodMillis, TimeUnit.MILLISECONDS);
        Date firstTime = new Date(System.currentTimeMillis() + initialDelayMillis);
        LOG.info("Automatic Podcast update scheduleds to run every " + hoursBetween + " hour(s), starting at " + firstTime);
    }

    /**
     * Creates a new Podcast channel.
     *
     * @param url The URL of the Podcast channel.
     */
    public void createChannel(String url) {
        PodcastChannel channel = new PodcastChannel(url);
        podcastDao.createChannel(channel);

        refresh(false);
    }

    /**
     * Returns all Podcast channels.
     *
     * @return Possibly empty array of all Podcast channels.
     */
    public PodcastChannel[] getAllChannels() {
        return podcastDao.getAllChannels();
    }

    /**
     * Returns all Podcast episodes for a given channel.
     *
     * @param channelId      The Podcast channel ID.
     * @param includeDeleted Whether to include logically deleted episodes in the result.
     * @return Possibly empty array of all Podcast episodes for the given channel.
     */
    public PodcastEpisode[] getEpisodes(int channelId, boolean includeDeleted) {
        PodcastEpisode[] all = podcastDao.getEpisodes(channelId);
        if (includeDeleted) {
            return all;
        }

        List<PodcastEpisode> filtered = new ArrayList<PodcastEpisode>();
        for (PodcastEpisode episode : all) {
            if (episode.getStatus() != PodcastEpisode.Status.DELETED) {
                filtered.add(episode);
            }
        }
        return filtered.toArray(new PodcastEpisode[0]);
    }


    private PodcastEpisode getEpisode(int episodeId, boolean includeDeleted) {
        PodcastEpisode episode = podcastDao.getEpisode(episodeId);
        if (episode == null) {
            return null;
        }
        if (episode.getStatus() == PodcastEpisode.Status.DELETED && !includeDeleted) {
            return null;
        }
        return episode;
    }

    private PodcastEpisode getEpisode(int channelId, String url) {
        if (url == null) {
            return null;
        }

        PodcastEpisode[] episodes = getEpisodes(channelId, true);
        for (PodcastEpisode episode : episodes) {
            if (url.equals(episode.getUrl())) {
                return episode;
            }
        }
        return null;
    }

    public void refresh(final boolean downloadEpisodes) {
        Runnable task = new Runnable() {
            public void run() {
                doRefresh(downloadEpisodes);
            }
        };
        refreshExecutor.submit(task);
    }

    private void doRefresh(boolean downloadEpisodes) {
        for (PodcastChannel channel : getAllChannels()) {

            InputStream in = null;
            try {
                URL url = new URL(channel.getUrl());
                in = url.openStream();
                Document document = new SAXBuilder().build(in);
                Element channelElement = document.getRootElement().getChild("channel");

                channel.setTitle(channelElement.getChildTextTrim("title"));
                channel.setDescription(channelElement.getChildTextTrim("description"));
                podcastDao.updateChannel(channel);

                refreshEpisodes(channel, channelElement.getChildren("item"));

            } catch (Exception x) {
                LOG.warn("Failed to get/parse RSS file for Podcast channel " + channel.getUrl(), x);
            } finally {
                IOUtils.closeQuietly(in);
            }
        }

        if (downloadEpisodes) {
            for (final PodcastChannel channel : getAllChannels()) {
                for (final PodcastEpisode episode : getEpisodes(channel.getId(), false)) {
                    if (episode.getStatus() == PodcastEpisode.Status.NEW && episode.getUrl() != null) {
                        Runnable task = new Runnable() {
                            public void run() {
                                downloadEpisode(channel, episode);
                            }
                        };
                        downloadExecutor.submit(task);
                    }
                }
            }
        }
    }

    private void refreshEpisodes(PodcastChannel channel, List<Element> episodeElements) {

        for (Element episodeElement : episodeElements) {

            String title = episodeElement.getChildTextTrim("title");
            String description = episodeElement.getChildTextTrim("description");
            String duration = episodeElement.getChildTextTrim("duration", ITUNES_NAMESPACE);

            Element enclosure = episodeElement.getChild("enclosure");
            String url = enclosure.getAttributeValue("url");
            if (getEpisode(channel.getId(), url) == null) {
                Long length = null;
                try {
                    length = new Long(enclosure.getAttributeValue("length"));
                } catch (Exception x) {
                    LOG.warn("Failed to parse enclosure length.", x);
                }

                Date date = null;
                try {
                    date = RSS_DATE_FORMAT.parse(episodeElement.getChildTextTrim("pubDate"));
                } catch (Exception x) {
                    LOG.warn("Failed to parse publish date.", x);
                }
                PodcastEpisode episode = new PodcastEpisode(null, channel.getId(), url, null, title, description, date,
                                                            duration, length, 0L, PodcastEpisode.Status.NEW);
                podcastDao.createEpisode(episode);
                LOG.info("Created Podcast episode " + title);
            }
        }
    }

    private void downloadEpisode(PodcastChannel channel, PodcastEpisode episode) {
        InputStream in = null;
        OutputStream out = null;

        try {

            URL url = new URL(episode.getUrl());
            in = url.openStream();
            File file = getFile(channel, episode);
            out = new FileOutputStream(file);

            episode.setStatus(PodcastEpisode.Status.DOWNLOADING);
            episode.setPath(file.getPath());
            podcastDao.updateEpisode(episode);

            LOG.info("Starting to download Podcast from " + episode.getUrl());

            byte[] buffer = new byte[4096];
            long bytesDownloaded = 0;
            int n;
            long nextLogCount = 30000L;

            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                bytesDownloaded += n;

                if (bytesDownloaded > nextLogCount) {
                    episode.setBytesDownloaded(bytesDownloaded);
                    nextLogCount += 30000L;
                    if (getEpisode(episode.getId(), false) == null) {
                        break;
                    }
                    podcastDao.updateEpisode(episode);
                }
            }

            if (getEpisode(episode.getId(), false) == null) {
                LOG.info("Podcast " + episode.getUrl() + " was deleted. Aborting download.");
                IOUtils.closeQuietly(out);
                file.delete();
            } else {
                episode.setBytesDownloaded(bytesDownloaded);
                podcastDao.updateEpisode(episode);
                LOG.info("Downloaded " + bytesDownloaded + " bytes from Podcast " + episode.getUrl());
                IOUtils.closeQuietly(out);
                episode.setStatus(PodcastEpisode.Status.DOWNLOADED);
                podcastDao.updateEpisode(episode);
                deleteObsoleteEpisodes(channel);
            }

        } catch (Exception x) {
            LOG.warn("Failed to download Podcast from " + episode.getUrl(), x);
            episode.setStatus(PodcastEpisode.Status.ERROR);
            podcastDao.updateEpisode(episode);
        } finally {
            IOUtils.closeQuietly(in);
            IOUtils.closeQuietly(out);
        }
    }

    private synchronized void deleteObsoleteEpisodes(PodcastChannel channel) {
        int episodeCount = settingsService.getPodcastEpisodeCount();
        if (episodeCount == -1) {
            return;
        }

        PodcastEpisode[] episodes = getEpisodes(channel.getId(), false);

        // Don't do anything if other episodes of the same channel is currently downloading.
        for (PodcastEpisode episode : episodes) {
            if (episode.getStatus() == PodcastEpisode.Status.DOWNLOADING) {
                return;
            }
        }

        int episodesToDelete = Math.max(0, episodes.length - episodeCount);
        for (int i = 0; i < episodesToDelete; i++) {
            deleteEpisode(episodes[i].getId(), true);
            LOG.info("Deleted old Podcast episode " + episodes[i].getUrl());
        }
    }

    private synchronized File getFile(PodcastChannel channel, PodcastEpisode episode) {

        File podcastDir = new File(settingsService.getPodcastFolder());
        File channelDir = new File(podcastDir, StringUtil.fileSystemSafe(channel.getTitle()));
        channelDir.mkdirs();

        String filename = StringUtil.getUrlFile(episode.getUrl());
        if (filename == null) {
            filename = episode.getTitle();
        }
        filename = StringUtil.fileSystemSafe(filename);
        String extension = FilenameUtils.getExtension(filename);
        filename = FilenameUtils.removeExtension(filename);
        if (StringUtils.isBlank(extension)) {
            extension = "mp3";
        }

        File file = new File(channelDir, filename + "." + extension);
        for (int i = 0; file.exists(); i++) {
            file = new File(channelDir, filename + i + "." + extension);
        }

        if (!securityService.isWriteAllowed(file)) {
            throw new SecurityException("Access denied to file " + file);
        }
        return file;
    }

    /**
     * Deletes the Podcast channel with the given ID.
     *
     * @param channelId The Podcast channel ID.
     */
    public void deleteChannel(int channelId) {
        // Delete all associated episodes (in case they have files that need to be deleted).
        PodcastEpisode[] episodes = getEpisodes(channelId, false);
        for (PodcastEpisode episode : episodes) {
            deleteEpisode(episode.getId(), false);
        }
        podcastDao.deleteChannel(channelId);
    }

    /**
     * Deletes the Podcast episode with the given ID.
     *
     * @param episodeId     The Podcast episode ID.
     * @param logicalDelete Whether to perform a logical delete by setting the
     *                      episode status to {@link PodcastEpisode.Status#DELETED}.
     */
    public void deleteEpisode(int episodeId, boolean logicalDelete) {
        PodcastEpisode episode = podcastDao.getEpisode(episodeId);
        if (episode == null) {
            return;
        }

        // Delete file.
        if (episode.getPath() != null) {
            File file = new File(episode.getPath());
            if (file.exists()) {
                file.delete();
                // TODO: Delete directory if empty?
            }
        }

        if (logicalDelete) {
            episode.setStatus(PodcastEpisode.Status.DELETED);
            podcastDao.updateEpisode(episode);
        } else {
            podcastDao.deleteEpisode(episodeId);
        }
    }

    public void setPodcastDao(PodcastDao podcastDao) {
        this.podcastDao = podcastDao;
    }

    public void setSettingsService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public void setSecurityService(SecurityService securityService) {
        this.securityService = securityService;
    }
}
