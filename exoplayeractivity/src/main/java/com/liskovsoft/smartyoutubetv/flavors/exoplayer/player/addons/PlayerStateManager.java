package com.liskovsoft.smartyoutubetv.flavors.exoplayer.player.addons;

import android.util.Pair;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.FixedTrackSelection;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.SelectionOverride;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.liskovsoft.smartyoutubetv.flavors.exoplayer.player.ExoPreferences;
import com.liskovsoft.smartyoutubetv.flavors.exoplayer.player.PlayerActivity;

import java.util.HashSet;
import java.util.Set;

public class PlayerStateManager {
    private static final TrackSelection.Factory FIXED_FACTORY = new FixedTrackSelection.Factory();
    private static final String AVC_CODEC = "avc";
    private static final String VP9_CODEC = "vp9";
    private static final String VP9_HDR_CODEC = "vp9.2";
    private static final int VIDEO_RENDERER_INDEX = 0;
    private final PlayerActivity mPlayerActivity;
    private final SimpleExoPlayer mPlayer;
    private final DefaultTrackSelector mSelector;
    private ExoPreferences mPrefs;
    private long MIN_PERSIST_DURATION_MILLIS = 5 * 60 * 1000; // don't save if total duration < 5 min
    private long MAX_TRAIL_DURATION_MILLIS = 2 * 60 * 1000; // don't save if 1 min of unseen video remains
    private long MAX_START_DURATION_MILLIS = 1 * 60 * 1000; // don't save if video just starts playing < 1 min
    private String mDefaultTrackId;

    public PlayerStateManager(PlayerActivity playerActivity, SimpleExoPlayer player, DefaultTrackSelector selector) {
        mPlayerActivity = playerActivity;
        mPlayer = player;
        mSelector = selector;
        mPrefs = new ExoPreferences(playerActivity);
    }

    public void restoreState(TrackGroupArray[] rendererTrackGroupArrays) {
        restoreTrackIndex(rendererTrackGroupArrays);
        restoreTrackPosition(rendererTrackGroupArrays);
    }

    private void restoreTrackPosition(TrackGroupArray[] groupArrays) {
        String title = mPlayerActivity.getMainTitle();
        long pos = mPrefs.getPosition(title);
        if (pos != C.TIME_UNSET)
            mPlayer.seekTo(pos);
    }

    /**
     * Restore track from prefs
     * @param rendererTrackGroupArrays source
     */
    private void restoreTrackIndex(TrackGroupArray[] rendererTrackGroupArrays) {
        if (trackGroupIsEmpty(rendererTrackGroupArrays)) {
            return;
        }
        Pair<Integer, Integer> trackGroupAndIndex = findProperTrack(rendererTrackGroupArrays);
        TrackGroupArray trackGroupArray = rendererTrackGroupArrays[VIDEO_RENDERER_INDEX];
        SelectionOverride override = new SelectionOverride(FIXED_FACTORY, trackGroupAndIndex.first, trackGroupAndIndex.second);
        mSelector.setSelectionOverride(VIDEO_RENDERER_INDEX, trackGroupArray, override);
    }

    ///**
    // * Switch track
    // * @param rendererTrackGroupArrays source of tracks
    // * @param selectedTrackId dash format id
    // * @param selectedTrackHeight
    // */
    //private void loadTrack(TrackGroupArray[] rendererTrackGroupArrays, String selectedTrackId, int selectedTrackHeight) {
    //    int rendererIndex = 0; // video
    //    if (trackGroupIsEmpty(rendererTrackGroupArrays)) {
    //        return;
    //    }
    //    Pair<Integer, Integer> trackGroupAndIndex = findProperTrack(rendererTrackGroupArrays, selectedTrackId, selectedTrackHeight);
    //    TrackGroupArray trackGroupArray = rendererTrackGroupArrays[rendererIndex];
    //    SelectionOverride override = new SelectionOverride(FIXED_FACTORY, trackGroupAndIndex.first, trackGroupAndIndex.second);
    //    mSelector.setSelectionOverride(rendererIndex, trackGroupArray, override);
    //}

    /**
     * Searches for the track by id and height.
     * @param allTracks source
     * @return pair consisted from track group index and track number
     */
    private Pair<Integer, Integer> findProperTrack(TrackGroupArray[] allTracks) {
        Set<MyFormat> fmts = findCandidates(allTracks);
        MyFormat fmt = findClosestTrack(fmts);
        mDefaultTrackId = fmt.id;
        return fmt.pair;
    }

    private Set<MyFormat> findCandidates(TrackGroupArray[] allTracks) {
        String trackId = mPrefs.getSelectedTrackId();
        int trackHeight = mPrefs.getSelectedTrackHeight();

        Set<MyFormat> result = new HashSet<>();

        TrackGroupArray groupArray = allTracks[VIDEO_RENDERER_INDEX];
        TrackGroup defaultTrackGroup = groupArray.get(0);

        // search the same tracks
        for (int j = 0; j < groupArray.length; j++) {
            TrackGroup trackGroup = groupArray.get(j);
            for (int i = 0; i < trackGroup.length; i++) {
                Format format = trackGroup.getFormat(i);

                if (tracksEquals(format.id, trackId)) {
                    result.clear();
                    result.add(new MyFormat(format, new Pair<>(j, i)));
                    return result;
                }

                if (heightEquals(format.height, trackHeight)) {
                    result.add(new MyFormat(format, new Pair<>(j, i)));
                }
            }
        }

        // if candidates not found, return topmost track
        if (result.isEmpty()) {
            int lastIdx = defaultTrackGroup.length - 1;
            result.add(new MyFormat(defaultTrackGroup.getFormat(lastIdx), new Pair<>(0, lastIdx)));
        }

        return result;
    }

    /**
     * Select format with same codec and highest bitrate. All track already have the same height.
     * @param fmts source (cannot be null)
     * @return best format (cannot be null)
     */
    private MyFormat findClosestTrack(Set<MyFormat> fmts) {
        String codecName = mPrefs.getSelectedTrackCodecs() == null ? "" : mPrefs.getSelectedTrackCodecs();

        // simplify codec name: use avc instead of avc.111333
        if (codecName.contains(AVC_CODEC)) {
            codecName = AVC_CODEC;
        } else if (codecName.contains(VP9_CODEC)) {
            codecName = VP9_CODEC;
        }

        MyFormat result = null;
        // select format with same codec and highest bitrate
        for (MyFormat fmt : fmts) {
            if (result == null) {
                result = fmt;
                continue;
            }

            // there is two levels of preference
            // 1) by codec (e.g. avc)
            // 2) by bitrate

            if (!result.codecs.contains(codecName) && fmt.codecs.contains(codecName)) {
                result = fmt;
                continue;
            }

            if (result.codecs.contains(codecName) && !fmt.codecs.contains(codecName)) {
                continue;
            }

            if (result.bitrate < fmt.bitrate) {
                result = fmt;
            }
        }
        return result;
    }

    private boolean heightEquals(int leftHeight, int rightHeight) {
        return Math.abs(leftHeight - rightHeight) <= 10; // ten-pixel precision
    }

    private boolean tracksEquals(String leftTrackId, String rightTrackId) {
        if (leftTrackId == null || rightTrackId == null) {
            return false;
        }

        return leftTrackId.equals(rightTrackId);
    }

    private boolean trackGroupIsEmpty(TrackGroupArray[] rendererTrackGroupArrays) {
        int rendererIndex = 0; // video
        TrackGroupArray groupArray = rendererTrackGroupArrays[rendererIndex];
        return groupArray.length == 0;
    }
    
    public void persistState() {
        if (mPrefs == null) {
            return;
        }

        persistTrackParams();
        persistTrackPosition();
    }

    private void persistTrackPosition() {
        long duration = mPlayer.getDuration();
        if (duration < MIN_PERSIST_DURATION_MILLIS) {
            return;
        }
        long position = mPlayer.getCurrentPosition();
        String title = mPlayerActivity.getMainTitle();
        boolean almostAllVideoSeen = (duration - position) < MAX_TRAIL_DURATION_MILLIS;
        boolean isVideoJustStarts = position < MAX_START_DURATION_MILLIS;
        if (almostAllVideoSeen || isVideoJustStarts) {
            mPrefs.resetPosition(title);
        } else {
            mPrefs.setPosition(title, position);
        }
    }

    private void persistTrackParams() {
        String trackId = extractCurrentTrackId();
        int height = extractCurrentTrackHeight();
        String codecs = extractCurrentTrackCodecs(); // there is a bug (null codecs) on some Live formats (strange id == "1/27")
        // mDefaultTrackId: usually this happens when video does not contain preferred format
        boolean isTrackChanged = codecs != null && trackId != null && !trackId.equals(mDefaultTrackId);
        if (isTrackChanged) {
            mPrefs.setSelectedTrackId(trackId);
            mPrefs.setSelectedTrackHeight(height);
            mPrefs.setSelectedTrackCodecs(codecs);
        }
    }

    private String extractCurrentTrackId() {
        Format videoFormat = mPlayer.getVideoFormat();
        if (videoFormat == null) {
            return null;
        }
        return videoFormat.id;
    }

    private String extractCurrentTrackCodecs() {
        Format videoFormat = mPlayer.getVideoFormat();
        if (videoFormat == null) {
            return null;
        }
        return videoFormat.codecs;
    }

    private int extractCurrentTrackHeight() {
        Format videoFormat = mPlayer.getVideoFormat();
        if (videoFormat == null) {
            return 0;
        }
        return videoFormat.height;
    }

    /**
     * Simple wrapper around {@link com.google.android.exoplayer2.Format}
     */
    private class MyFormat {
        public final String id;
        public final int bitrate;
        public final float frameRate;
        public final String codecs;
        public final Pair<Integer, Integer> pair;

        public MyFormat(Format format, Pair<Integer, Integer> pair) {
            id = format.id;
            bitrate = format.bitrate;
            frameRate = format.frameRate;
            codecs = format.codecs;
            this.pair = pair;
        }
    }
}
