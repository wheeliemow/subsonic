/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package net.sourceforge.subsonic.ajax;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.sourceforge.subsonic.dao.MediaFileDao;
import net.sourceforge.subsonic.service.MediaFileService;
import net.sourceforge.subsonic.service.SecurityService;
import org.directwebremoting.WebContextFactory;
import org.springframework.web.servlet.support.RequestContextUtils;

import net.sourceforge.subsonic.domain.MediaFile;
import net.sourceforge.subsonic.domain.Player;
import net.sourceforge.subsonic.domain.PlayQueue;
import net.sourceforge.subsonic.service.JukeboxService;
import net.sourceforge.subsonic.service.PlayerService;
import net.sourceforge.subsonic.service.TranscodingService;
import net.sourceforge.subsonic.service.SettingsService;
import net.sourceforge.subsonic.util.StringUtil;

/**
 * Provides AJAX-enabled services for manipulating the playlist of a player.
 * This class is used by the DWR framework (http://getahead.ltd.uk/dwr/).
 *
 * @author Sindre Mehus
 */
public class PlayQueueService {

    private PlayerService playerService;
    private JukeboxService jukeboxService;
    private TranscodingService transcodingService;
    private SettingsService settingsService;
    private MediaFileService mediaFileService;
    private SecurityService securityService;
    private MediaFileDao mediaFileDao;

    /**
     * Returns the playlist for the player of the current user.
     *
     * @return The playlist.
     */
    public PlaylistInfo getPlaylist() throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        Player player = getCurrentPlayer(request, response);
        return convert(request, player, false);
    }

    public PlaylistInfo start() throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        return doStart(request, response);
    }

    public PlaylistInfo doStart(HttpServletRequest request, HttpServletResponse response) throws Exception {
        Player player = getCurrentPlayer(request, response);
        player.getPlayQueue().setStatus(PlayQueue.Status.PLAYING);
        return convert(request, player, true);
    }

    public PlaylistInfo stop() throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        return doStop(request, response);
    }

    public PlaylistInfo doStop(HttpServletRequest request, HttpServletResponse response) throws Exception {
        Player player = getCurrentPlayer(request, response);
        player.getPlayQueue().setStatus(PlayQueue.Status.STOPPED);
        return convert(request, player, true);
    }

    public PlaylistInfo skip(int index) throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        return doSkip(request, response, index, 0);
    }

    public PlaylistInfo doSkip(HttpServletRequest request, HttpServletResponse response, int index, int offset) throws Exception {
        Player player = getCurrentPlayer(request, response);
        player.getPlayQueue().setIndex(index);
        boolean serverSidePlaylist = !player.isExternalWithPlaylist();
        return convert(request, player, serverSidePlaylist, offset);
    }

    public PlaylistInfo play(int id) throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();

        Player player = getCurrentPlayer(request, response);
        MediaFile file = mediaFileService.getMediaFile(id);
        List<MediaFile> files = mediaFileService.getDescendantsOf(file, true);
        if (player.isWeb()) {
            removeVideoFiles(files);
        }
        player.getPlayQueue().addFiles(false, files);
        player.getPlayQueue().setRandomSearchCriteria(null);
        return convert(request, player, true);
    }

    public PlaylistInfo playRandom(int id, int count) throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();

        MediaFile file = mediaFileService.getMediaFile(id);
        List<MediaFile> randomFiles = getRandomChildren(file, count);
        Player player = getCurrentPlayer(request, response);
        player.getPlayQueue().addFiles(false, randomFiles);
        player.getPlayQueue().setRandomSearchCriteria(null);
        return convert(request, player, true);
    }

    public PlaylistInfo add(int id) throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        return doAdd(request, response, new int[]{id});
    }

    public PlaylistInfo doAdd(HttpServletRequest request, HttpServletResponse response, int[] ids) throws Exception {
        Player player = getCurrentPlayer(request, response);
        List<MediaFile> files = new ArrayList<MediaFile>(ids.length);
        for (int id : ids) {
            MediaFile ancestor = mediaFileService.getMediaFile(id);
            files.addAll(mediaFileService.getDescendantsOf(ancestor, true));
        }
        if (player.isWeb()) {
            removeVideoFiles(files);
        }
        player.getPlayQueue().addFiles(true, files);
        player.getPlayQueue().setRandomSearchCriteria(null);
        return convert(request, player, false);
    }
    
    public PlaylistInfo doSet(HttpServletRequest request, HttpServletResponse response, int[] ids) throws Exception {
        Player player = getCurrentPlayer(request, response);
        PlayQueue playQueue = player.getPlayQueue();
        MediaFile currentFile = playQueue.getCurrentFile();
        PlayQueue.Status status = playQueue.getStatus();

        playQueue.clear();
        PlaylistInfo result = doAdd(request, response, ids);

        int index = currentFile == null ? -1 : playQueue.getFiles().indexOf(currentFile);
        playQueue.setIndex(index);
        playQueue.setStatus(status);
        return result;
    }

    public PlaylistInfo clear() throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        return doClear(request, response);
    }

    public PlaylistInfo doClear(HttpServletRequest request, HttpServletResponse response) throws Exception {
        Player player = getCurrentPlayer(request, response);
        player.getPlayQueue().clear();
        boolean serverSidePlaylist = !player.isExternalWithPlaylist();
        return convert(request, player, serverSidePlaylist);
    }

    public PlaylistInfo shuffle() throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        return doShuffle(request, response);
    }

    public PlaylistInfo doShuffle(HttpServletRequest request, HttpServletResponse response) throws Exception {
        Player player = getCurrentPlayer(request, response);
        player.getPlayQueue().shuffle();
        return convert(request, player, false);
    }

    public PlaylistInfo remove(int index) throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        return doRemove(request, response, index);
    }

    public PlaylistInfo toggleStar(int index) throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        Player player = getCurrentPlayer(request, response);

        MediaFile file = player.getPlayQueue().getFile(index);
        String username = securityService.getCurrentUsername(request);
        boolean starred = mediaFileDao.getMediaFileStarredDate(file.getId(), username) != null;
        if (starred) {
            mediaFileDao.unstarMediaFile(file.getId(), username);
        } else {
            mediaFileDao.starMediaFile(file.getId(), username);
        }
        return convert(request, player, false);
    }

    public PlaylistInfo doRemove(HttpServletRequest request, HttpServletResponse response, int index) throws Exception {
        Player player = getCurrentPlayer(request, response);
        player.getPlayQueue().removeFileAt(index);
        return convert(request, player, false);
    }

    public PlaylistInfo removeMany(int[] indexes) throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        Player player = getCurrentPlayer(request, response);
        for (int i = indexes.length - 1; i >= 0; i--) {
            player.getPlayQueue().removeFileAt(indexes[i]);
        }
        return convert(request, player, false);
    }

    public PlaylistInfo up(int index) throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        Player player = getCurrentPlayer(request, response);
        player.getPlayQueue().moveUp(index);
        return convert(request, player, false);
    }

    public PlaylistInfo down(int index) throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        Player player = getCurrentPlayer(request, response);
        player.getPlayQueue().moveDown(index);
        return convert(request, player, false);
    }

    public PlaylistInfo toggleRepeat() throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        Player player = getCurrentPlayer(request, response);
        player.getPlayQueue().setRepeatEnabled(!player.getPlayQueue().isRepeatEnabled());
        return convert(request, player, false);
    }

    public PlaylistInfo undo() throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        Player player = getCurrentPlayer(request, response);
        player.getPlayQueue().undo();
        boolean serverSidePlaylist = !player.isExternalWithPlaylist();
        return convert(request, player, serverSidePlaylist);
    }

    public PlaylistInfo sortByTrack() throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        Player player = getCurrentPlayer(request, response);
        player.getPlayQueue().sort(PlayQueue.SortOrder.TRACK);
        return convert(request, player, false);
    }

    public PlaylistInfo sortByArtist() throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        Player player = getCurrentPlayer(request, response);
        player.getPlayQueue().sort(PlayQueue.SortOrder.ARTIST);
        return convert(request, player, false);
    }

    public PlaylistInfo sortByAlbum() throws Exception {
        HttpServletRequest request = WebContextFactory.get().getHttpServletRequest();
        HttpServletResponse response = WebContextFactory.get().getHttpServletResponse();
        Player player = getCurrentPlayer(request, response);
        player.getPlayQueue().sort(PlayQueue.SortOrder.ALBUM);
        return convert(request, player, false);
    }

    public void setGain(float gain) {
        jukeboxService.setGain(gain);
    }

    private List<MediaFile> getRandomChildren(MediaFile file, int count) throws IOException {
        List<MediaFile> children = mediaFileService.getDescendantsOf(file, false);
        removeVideoFiles(children);

        if (children.isEmpty()) {
            return children;
        }
        Collections.shuffle(children);
        return children.subList(0, Math.min(count, children.size()));
    }

    private void removeVideoFiles(List<MediaFile> files) {
        Iterator<MediaFile> iterator = files.iterator();
        while (iterator.hasNext()) {
            MediaFile file = iterator.next();
            if (file.isVideo()) {
                iterator.remove();
            }
        }
    }

    private PlaylistInfo convert(HttpServletRequest request, Player player, boolean sendM3U) throws Exception {
        return convert(request, player, sendM3U, 0);
    }

    private PlaylistInfo convert(HttpServletRequest request, Player player, boolean sendM3U, int offset) throws Exception {
        String url = request.getRequestURL().toString();

        if (sendM3U && player.isJukebox()) {
            jukeboxService.updateJukebox(player, offset);
        }
        boolean isCurrentPlayer = player.getIpAddress() != null && player.getIpAddress().equals(request.getRemoteAddr());

        boolean m3uSupported = player.isExternal() || player.isExternalWithPlaylist();
        sendM3U = player.isAutoControlEnabled() && m3uSupported && isCurrentPlayer && sendM3U;
        Locale locale = RequestContextUtils.getLocale(request);

        List<PlaylistInfo.Entry> entries = new ArrayList<PlaylistInfo.Entry>();
        PlayQueue playQueue = player.getPlayQueue();
        for (MediaFile file : playQueue.getFiles()) {
            String albumUrl = url.replaceFirst("/dwr/.*", "/main.view?id=" + file.getId());
            String streamUrl = url.replaceFirst("/dwr/.*", "/stream?player=" + player.getId() + "&id=" + file.getId());

            // Rewrite URLs in case we're behind a proxy.
            if (settingsService.isRewriteUrlEnabled()) {
                String referer = request.getHeader("referer");
                albumUrl = StringUtil.rewriteUrl(albumUrl, referer);
                streamUrl = StringUtil.rewriteUrl(streamUrl, referer);
            }

            String format = formatFormat(player, file);
            String username = securityService.getCurrentUsername(request);
            boolean starred = mediaFileService.getMediaFileStarredDate(file.getId(), username) != null;
            entries.add(new PlaylistInfo.Entry(file.getTrackNumber(), file.getTitle(), file.getArtist(),
                    file.getAlbumName(), file.getGenre(), file.getYear(), formatBitRate(file),
                    file.getDurationSeconds(), file.getDurationString(), format, formatContentType(format),
                    formatFileSize(file.getFileSize(), locale), starred, albumUrl, streamUrl));
        }
        boolean isStopEnabled = playQueue.getStatus() == PlayQueue.Status.PLAYING && !player.isExternalWithPlaylist();
        float gain = jukeboxService.getGain();
        return new PlaylistInfo(entries, playQueue.getIndex(), isStopEnabled, playQueue.isRepeatEnabled(), sendM3U, gain);
    }

    private String formatFileSize(Long fileSize, Locale locale) {
        if (fileSize == null) {
            return null;
        }
        return StringUtil.formatBytes(fileSize, locale);
    }

    private String formatFormat(Player player, MediaFile file) {
        return transcodingService.getSuffix(player, file, null);
    }

    private String formatContentType(String format) {
        return StringUtil.getMimeType(format);
    }

    private String formatBitRate(MediaFile mediaFile) {
        if (mediaFile.getBitRate() == null) {
            return null;
        }
        if (mediaFile.isVariableBitRate()) {
            return mediaFile.getBitRate() + " Kbps vbr";
        }
        return mediaFile.getBitRate() + " Kbps";
    }

    private Player getCurrentPlayer(HttpServletRequest request, HttpServletResponse response) {
        return playerService.getPlayer(request, response);
    }

    public void setPlayerService(PlayerService playerService) {
        this.playerService = playerService;
    }

    public void setMediaFileService(MediaFileService mediaFileService) {
        this.mediaFileService = mediaFileService;
    }

    public void setJukeboxService(JukeboxService jukeboxService) {
        this.jukeboxService = jukeboxService;
    }

    public void setTranscodingService(TranscodingService transcodingService) {
        this.transcodingService = transcodingService;
    }

    public void setSettingsService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public void setSecurityService(SecurityService securityService) {
        this.securityService = securityService;
    }

    public void setMediaFileDao(MediaFileDao mediaFileDao) {
        this.mediaFileDao = mediaFileDao;
    }
}