# MyCinema QA movie fixture

`prepareQaMedia` generates `build/qa-media/mycinema-qa.mp4` with FFmpeg's `testsrc2` video source and `sine` audio source. The resulting eight-second colour/test-tone movie is generated specifically for MyCinema release verification and contains no third-party film, music, image, or voice recording.

The generated MP4 is not committed or included in a MyCinema release. The clean-profile client lane serves it only on `127.0.0.1` while the test is running.
