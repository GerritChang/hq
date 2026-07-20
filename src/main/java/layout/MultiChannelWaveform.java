package layout;

import analysis.WaveformDataInput;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;

/** 20-channel synchronized waveform viewer for very large, time-ordered data sets. */
public final class MultiChannelWaveform {
    private static final int CHANNELS = 20;
    private static final long SAMPLES_PER_CHANNEL = 25_000_000L;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("20 通道时域波形 — 每通道 2500 万点");
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            WaveformPanel waveform = new WaveformPanel(new WaveformDataInput(CHANNELS, SAMPLES_PER_CHANNEL));
            JScrollPane scrollPane = new JScrollPane(waveform,
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scrollPane.getVerticalScrollBar().setUnitIncrement(30);
            scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent e) {
                    Dimension extent = scrollPane.getViewport().getExtentSize();
                    // The complete panel is four viewport-heights: exactly 5 of 20 channels are visible.
                    int fullHeight = Math.max(500, extent.height * CHANNELS / 5);
                    Dimension wanted = new Dimension(Math.max(1, extent.width), fullHeight);
                    if (!wanted.equals(waveform.getPreferredSize())) {
                        waveform.setPreferredSize(wanted);
                        waveform.revalidate();
                    }
                }
            });
            JPanel content = new JPanel(new BorderLayout());
            content.add(createToolbar(waveform), BorderLayout.NORTH);
            content.add(scrollPane, BorderLayout.CENTER);
            frame.setContentPane(content);
            frame.setSize(1400, 950);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private static JToolBar createToolbar(WaveformPanel waveform) {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 8));
        JTextField rangeStart = new JTextField(10);
        JTextField rangeEnd = new JTextField(10);
        rangeStart.setToolTipText("留空表示当前视野起点");
        rangeEnd.setToolTipText("留空表示当前视野终点");
        rangeStart.setMaximumSize(rangeStart.getPreferredSize());
        rangeEnd.setMaximumSize(rangeEnd.getPreferredSize());
        ActionListener applyRange = e -> {
            if (!waveform.applyInputRange(rangeStart.getText(), rangeEnd.getText())) {
                Toolkit.getDefaultToolkit().beep();
                JOptionPane.showMessageDialog(toolbar,
                        "请输入有效范围：0 ≤ 起始点 < 结束点 < " + SAMPLES_PER_CHANNEL,
                        "范围无效", JOptionPane.WARNING_MESSAGE);
            }
        };
        rangeStart.addActionListener(applyRange);
        rangeEnd.addActionListener(applyRange);
        JButton back = new JButton("◀ 缩放后退");
        JButton forward = new JButton("缩放前进 ▶");
        JToggleButton drag = new JToggleButton("✥ 拖拽");
        JToggleButton frequency = new JToggleButton("显示 FFT");
        JToggleButton psd = new JToggleButton("显示 PSD");
        back.setToolTipText("返回上一个缩放视图");
        forward.setToolTipText("前往下一个缩放视图");
        drag.setToolTipText("开启后使用鼠标左键平移全部通道");
        frequency.setToolTipText("开启或隐藏 FFT 频域图");
        psd.setToolTipText("开启或隐藏功率谱密度图");
        back.addActionListener(e -> waveform.goBack());
        forward.addActionListener(e -> waveform.goForward());
        drag.addActionListener(e -> waveform.setDragMode(drag.isSelected()));
        frequency.addActionListener(e -> waveform.setFrequencyVisible(frequency.isSelected()));
        psd.addActionListener(e -> waveform.setPsdVisible(psd.isSelected()));
        toolbar.add(new JLabel("起始点 "));
        toolbar.add(rangeStart);
        toolbar.add(new JLabel("  结束点 "));
        toolbar.add(rangeEnd);
        toolbar.addSeparator();
        toolbar.add(back);
        toolbar.add(forward);
        toolbar.addSeparator();
        toolbar.add(drag);
        toolbar.addSeparator();
        toolbar.add(frequency);
        toolbar.add(psd);
        toolbar.add(Box.createHorizontalGlue());
        return toolbar;
    }

    /** One annotation represents a synchronized cursor across every channel. */
    private record Annotation(long sample, String text) { }
    private record SpectralAnnotation(double frequency, boolean psd) { }
    private record RenderResult(BufferedImage image, long millis, double[][] spectra, double[][] psds) { }
    private record ViewState(long start, long length, double frequencyStart, double frequencySpan) { }

    private static final class WaveformPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final int LEFT = 76;
        private static final int COLUMN_GAP = 58;
        private static final int RIGHT = 12;
        private static final int TOP = 22;
        private static final int BOTTOM = 30;
        private final WaveformDataInput source;
        private final List<Annotation> annotations = new ArrayList<>();
        private final List<SpectralAnnotation> spectralAnnotations = new ArrayList<>();
        private final Deque<ViewState> backHistory = new ArrayDeque<>();
        private final Deque<ViewState> forwardHistory = new ArrayDeque<>();
        private final Timer renderDelay;
        private BufferedImage image;
        private double[][] spectrumCache;
        private double[][] psdCache;
        private SwingWorker<RenderResult, Void> worker;
        private long viewStart;
        private long viewLength;
        private double frequencyStart;
        private double frequencySpan = 0.5;
        private Point dragStart;
        private long dragViewStart;
        private Point selectionStart;
        private Point selectionEnd;
        private boolean selectionDragged;
        private boolean frequencySelection;
        private int selectionFrequencyLeft;
        private int selectionFrequencyRight;
        private boolean dragMode;
        private boolean frequencyVisible;
        private boolean psdVisible;
        private String status = "准备渲染";

        WaveformPanel(WaveformDataInput source) {
            this.source = source;
            viewLength = source.sampleCount();
            setBackground(new Color(11, 15, 23));
            setToolTipText("");
            renderDelay = new Timer(100, e -> startRender());
            renderDelay.setRepeats(false);

            addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent e) { renderDelay.restart(); }
            });
            addMouseWheelListener(this::zoomAtMouse);
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && !e.isShiftDown() && !dragMode) {
                        boolean inFrequency = isFrequencyX(e.getX());
                        boolean inPsd = isPsdX(e.getX());
                        if (e.getX() < LEFT || (!inFrequency && !inPsd && e.getX() > timePlotRight())) return;
                        selectionStart = e.getPoint();
                        selectionEnd = e.getPoint();
                        frequencySelection = inFrequency || inPsd;
                        selectionFrequencyLeft = inPsd ? psdPlotLeft() : frequencyPlotLeft();
                        selectionFrequencyRight = inPsd ? psdPlotRight() : frequencyPlotRight();
                        selectionDragged = false;
                    } else if (SwingUtilities.isMiddleMouseButton(e)
                            || (SwingUtilities.isLeftMouseButton(e) && (e.isShiftDown() || dragMode))) {
                        dragStart = e.getPoint();
                        dragViewStart = viewStart;
                        rememberView();
                    }
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (selectionStart != null) {
                        int minimumX = frequencySelection ? selectionFrequencyLeft : LEFT;
                        int maximumX = frequencySelection ? selectionFrequencyRight : timePlotRight();
                        selectionEnd = new Point(Math.max(minimumX, Math.min(maximumX, e.getX())), e.getY());
                        selectionDragged |= Math.abs(selectionEnd.x - selectionStart.x) >= 6;
                        repaint();
                    } else if (dragStart != null) {
                        int plotWidth = timePlotWidth();
                        long delta = Math.round((dragStart.x - e.getX()) * (double) viewLength / plotWidth);
                        viewStart = clampStart(dragViewStart + delta, viewLength);
                        renderDelay.restart();
                        repaint();
                    }
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (selectionStart != null) {
                        if (selectionDragged) zoomToSelection();
                        else addAnnotation(e);
                        selectionStart = selectionEnd = null;
                        repaint();
                    }
                    dragStart = null;
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) removeNearestAnnotation(e);
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private void zoomAtMouse(MouseWheelEvent e) {
            if (!e.isControlDown()) {
                Container ancestor = SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
                if (ancestor instanceof JScrollPane scrollPane) {
                    JScrollBar bar = scrollPane.getVerticalScrollBar();
                    int step = Math.max(bar.getUnitIncrement(), getHeight() / source.channelCount() / 3);
                    bar.setValue(bar.getValue() + e.getWheelRotation() * step);
                    e.consume();
                }
                return;
            }
            int plotWidth = timePlotWidth();
            if (isFrequencyX(e.getX()) || isPsdX(e.getX())) {
                int frequencyColumnLeft = isPsdX(e.getX()) ? psdPlotLeft() : frequencyPlotLeft();
                int frequencyColumnWidth = isPsdX(e.getX()) ? psdPlotWidth() : frequencyPlotWidth();
                double fraction = Math.max(0, Math.min(1,
                        (e.getX() - frequencyColumnLeft) / (double) Math.max(1, frequencyColumnWidth)));
                double anchor = frequencyStart + fraction * frequencySpan;
                double newSpan = Math.max(0.000_001, Math.min(0.5,
                        frequencySpan * Math.pow(1.35, e.getPreciseWheelRotation())));
                rememberView();
                frequencyStart = Math.max(0, Math.min(0.5 - newSpan, anchor - fraction * newSpan));
                frequencySpan = newSpan;
                startRender();
                e.consume();
                return;
            }
            double fraction = Math.max(0, Math.min(1, (e.getX() - LEFT) / (double) plotWidth));
            long anchor = viewStart + Math.round(fraction * viewLength);
            double factor = Math.pow(1.35, e.getPreciseWheelRotation());
            long newLength = Math.max(100, Math.min(source.sampleCount(), Math.round(viewLength * factor)));
            rememberView();
            viewStart = clampStart(anchor - Math.round(fraction * newLength), newLength);
            viewLength = newLength;
            renderDelay.restart();
            repaint();
            e.consume();
        }

        private long clampStart(long candidate, long length) {
            return Math.max(0, Math.min(source.sampleCount() - length, candidate));
        }

        private void addAnnotation(MouseEvent e) {
            int channel = channelAt(e.getY());
            if (channel < 0) return;
            if (isFrequencyX(e.getX()) || isPsdX(e.getX())) {
                boolean psd = isPsdX(e.getX());
                int left = psd ? psdPlotLeft() : frequencyPlotLeft();
                int width = psd ? psdPlotWidth() : frequencyPlotWidth();
                double fraction = Math.max(0, Math.min(1, (e.getX() - left) / (double) width));
                double frequency = frequencyStart + fraction * frequencySpan;
                double duplicateTolerance = frequencySpan / Math.max(1, width) * 0.25;
                if (spectralAnnotations.stream().anyMatch(a -> a.psd == psd
                        && Math.abs(a.frequency - frequency) <= duplicateTolerance)) return;
                spectralAnnotations.add(new SpectralAnnotation(frequency, psd));
                repaint();
                return;
            }
            if (e.getX() < LEFT || e.getX() > timePlotRight()) return;
            long sample = sampleAt(e.getX());
            if (annotations.stream().anyMatch(annotation -> annotation.sample == sample)) return;
            annotations.add(new Annotation(sample, "X=" + String.format("%,d", sample)));
            repaint();
        }

        private void zoomToSelection() {
            int minimumX = frequencySelection ? selectionFrequencyLeft : LEFT;
            int maximumX = frequencySelection ? selectionFrequencyRight : timePlotRight();
            int x0 = Math.max(minimumX, Math.min(selectionStart.x, selectionEnd.x));
            int x1 = Math.min(maximumX, Math.max(selectionStart.x, selectionEnd.x));
            if (x1 - x0 < 6) return;
            if (frequencySelection) {
                double oldStart = frequencyStart;
                double oldSpan = frequencySpan;
                double startFraction = (x0 - selectionFrequencyLeft) /
                        (double) Math.max(1, selectionFrequencyRight - selectionFrequencyLeft);
                double endFraction = (x1 - selectionFrequencyLeft) /
                        (double) Math.max(1, selectionFrequencyRight - selectionFrequencyLeft);
                rememberView();
                frequencyStart = oldStart + startFraction * oldSpan;
                frequencySpan = Math.max(0.000_001, (endFraction - startFraction) * oldSpan);
                startRender();
                return;
            }
            long newStart = sampleAt(x0);
            long newEnd = sampleAt(x1);
            rememberView();
            viewStart = newStart;
            viewLength = Math.max(100, newEnd - newStart + 1);
            viewStart = clampStart(viewStart, viewLength);
            startRender();
        }

        void setDragMode(boolean enabled) {
            dragMode = enabled;
            setCursor(enabled ? Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) : Cursor.getDefaultCursor());
        }

        void setFrequencyVisible(boolean visible) {
            frequencyVisible = visible;
            spectrumCache = null;
            startRender();
            repaint();
        }

        void setPsdVisible(boolean visible) {
            psdVisible = visible;
            psdCache = null;
            startRender();
            repaint();
        }

        private void rememberView() {
            ViewState current = new ViewState(viewStart, viewLength, frequencyStart, frequencySpan);
            if (backHistory.peekLast() == null || !backHistory.peekLast().equals(current)) {
                backHistory.addLast(current);
                while (backHistory.size() > 100) backHistory.removeFirst();
            }
            forwardHistory.clear();
        }

        void goBack() {
            if (backHistory.isEmpty()) return;
            forwardHistory.addLast(new ViewState(viewStart, viewLength, frequencyStart, frequencySpan));
            applyView(backHistory.removeLast());
        }

        void goForward() {
            if (forwardHistory.isEmpty()) return;
            backHistory.addLast(new ViewState(viewStart, viewLength, frequencyStart, frequencySpan));
            applyView(forwardHistory.removeLast());
        }

        private void applyView(ViewState state) {
            viewStart = state.start;
            viewLength = state.length;
            frequencyStart = state.frequencyStart;
            frequencySpan = state.frequencySpan;
            startRender();
        }

        boolean applyInputRange(String startText, String endText) {
            try {
                long start = startText.isBlank() ? viewStart : parseSample(startText);
                long end = endText.isBlank() ? viewStart + viewLength - 1 : parseSample(endText);
                if (start < 0 || end >= source.sampleCount() || start >= end) return false;
                rememberView();
                viewStart = start;
                viewLength = end - start + 1;
                startRender();
                return true;
            } catch (NumberFormatException ex) {
                return false;
            }
        }

        private static long parseSample(String text) {
            return Long.parseLong(text.trim().replace(",", "").replace("_", ""));
        }

        private void removeNearestAnnotation(MouseEvent e) {
            if (isFrequencyX(e.getX()) || isPsdX(e.getX())) {
                boolean psd = isPsdX(e.getX());
                int left = psd ? psdPlotLeft() : frequencyPlotLeft();
                int width = psd ? psdPlotWidth() : frequencyPlotWidth();
                double frequency = frequencyStart + Math.max(0, Math.min(1,
                        (e.getX() - left) / (double) width)) * frequencySpan;
                double tolerance = frequencySpan / Math.max(1, width) * 8;
                spectralAnnotations.stream().filter(a -> a.psd == psd
                                && Math.abs(a.frequency - frequency) <= tolerance)
                        .min((a, b) -> Double.compare(Math.abs(a.frequency - frequency),
                                Math.abs(b.frequency - frequency)))
                        .ifPresent(a -> { spectralAnnotations.remove(a); repaint(); });
                return;
            }
            long sample = sampleAt(e.getX());
            long tolerance = Math.max(1, viewLength / Math.max(1, getWidth()) * 8);
            annotations.stream().filter(a -> Math.abs(a.sample - sample) <= tolerance)
                    .min((a, b) -> Long.compare(Math.abs(a.sample - sample), Math.abs(b.sample - sample)))
                    .ifPresent(a -> { annotations.remove(a); repaint(); });
        }

        private int channelAt(int y) {
            int plotHeight = getHeight() - TOP - BOTTOM;
            if (y < TOP || y >= TOP + plotHeight) return -1;
            return Math.min(source.channelCount() - 1, (y - TOP) * source.channelCount() / plotHeight);
        }

        private long sampleAt(int x) {
            double f = Math.max(0, Math.min(1, (x - LEFT) / (double) timePlotWidth()));
            return Math.min(source.sampleCount() - 1, viewStart + Math.round(f * (viewLength - 1)));
        }

        private int timePlotWidth() {
            int columns = 1 + (frequencyVisible ? 1 : 0) + (psdVisible ? 1 : 0);
            return Math.max(1, (getWidth() - LEFT - (columns - 1) * COLUMN_GAP - RIGHT) / columns);
        }

        private int timePlotRight() { return LEFT + timePlotWidth(); }

        private int frequencyPlotLeft() { return timePlotRight() + COLUMN_GAP; }
        private int frequencyPlotRight() { return frequencyPlotLeft() + timePlotWidth(); }
        private int frequencyPlotWidth() { return Math.max(1, frequencyPlotRight() - frequencyPlotLeft()); }
        private int psdPlotLeft() {
            return timePlotRight() + COLUMN_GAP + (frequencyVisible ? timePlotWidth() + COLUMN_GAP : 0);
        }
        private int psdPlotRight() { return psdPlotLeft() + timePlotWidth(); }
        private int psdPlotWidth() { return Math.max(1, psdPlotRight() - psdPlotLeft()); }
        private boolean isFrequencyX(int x) {
            return frequencyVisible && x >= frequencyPlotLeft() && x <= frequencyPlotRight();
        }
        private boolean isPsdX(int x) { return psdVisible && x >= psdPlotLeft() && x <= psdPlotRight(); }

        private void startRender() {
            int w = getWidth(), h = getHeight();
            if (w <= LEFT || h <= TOP + BOTTOM) return;
            if (worker != null) worker.cancel(true);
            long requestedStart = viewStart, requestedLength = viewLength;
            double requestedFrequencyStart = frequencyStart, requestedFrequencySpan = frequencySpan;
            boolean requestedFrequencyVisible = frequencyVisible;
            boolean requestedPsdVisible = psdVisible;
            status = "正在生成 20 通道连续折线…";
            worker = new SwingWorker<>() {
                @Override protected RenderResult doInBackground() {
                    long began = System.nanoTime();
                    BufferedImage target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    int[] pixels = ((DataBufferInt) target.getRaster().getDataBuffer()).getData();
                    java.util.Arrays.fill(pixels, 0x0B0F17);
                    int columns = 1 + (requestedFrequencyVisible ? 1 : 0) + (requestedPsdVisible ? 1 : 0);
                    int plotW = Math.max(1, (w - LEFT - (columns - 1) * COLUMN_GAP - RIGHT) / columns);
                    int frequencyLeft = LEFT + plotW + COLUMN_GAP;
                    int psdLeft = LEFT + plotW + COLUMN_GAP
                            + (requestedFrequencyVisible ? plotW + COLUMN_GAP : 0);
                    int plotH = h - TOP - BOTTOM;
                    double[][] renderedSpectra = new double[source.channelCount()][];
                    double[][] renderedPsds = new double[source.channelCount()][];
                    for (int ch = 0; ch < source.channelCount(); ch++) {
                        int bandTop = TOP + ch * plotH / source.channelCount();
                        int bandBottom = TOP + (ch + 1) * plotH / source.channelCount();
                        int y0 = bandTop + 2;
                        int y1 = Math.max(y0 + 3, bandBottom - 13);
                        int mid = (y0 + y1) / 2;
                        drawHorizontal(pixels, w, LEFT, LEFT + plotW, mid, 0x253044);
                        int visiblePoints = (int) Math.min(requestedLength, plotW);
                        int previousX = LEFT;
                        int previousY = mid;
                        for (int point = 0; point < visiblePoints; point++) {
                            if ((point & 127) == 0 && isCancelled()) throw new CancellationException();
                            long sample = requestedStart + point * (requestedLength - 1)
                                    / Math.max(1, visiblePoints - 1);
                            sample = Math.min(source.sampleCount() - 1, sample);
                            int currentX = LEFT + (int) Math.round(point * (plotW - 1.0)
                                    / Math.max(1, visiblePoints - 1));
                            int currentY = Math.max(y0 + 1, Math.min(y1 - 1,
                                    valueToY(source.valueAt(ch, sample), mid, y1 - y0)));
                            if (point == 0) {
                                pixels[currentY * w + currentX] = channelColor(ch);
                            } else {
                                drawLine(pixels, w, previousX, previousY, currentX, currentY,
                                        channelColor(ch));
                            }
                            // When zoomed in, make every original sample visibly a single point.
                            if (requestedLength <= plotW) {
                                drawPoint(pixels, w, currentX, currentY, w, y0 + 1, y1 - 1, channelColor(ch));
                            }
                            previousX = currentX;
                            previousY = currentY;
                        }
                        if (requestedFrequencyVisible) {
                            double[] spectrum = calculateSpectrum(source, ch, requestedStart, requestedLength, 1024);
                            renderedSpectra[ch] = spectrum;
                            int previousSpectrumY = y1;
                            int firstBin = Math.max(0, (int) Math.floor(requestedFrequencyStart * 2 * (spectrum.length - 1)));
                            int lastBin = Math.min(spectrum.length - 1, (int) Math.ceil(
                                    (requestedFrequencyStart + requestedFrequencySpan) * 2 * (spectrum.length - 1)));
                            int previousSpectrumX = frequencyLeft;
                            for (int bin = firstBin; bin <= lastBin; bin++) {
                                double binFrequency = bin * 0.5 / Math.max(1, spectrum.length - 1);
                                int currentX = frequencyLeft + (int) Math.round(
                                        (binFrequency - requestedFrequencyStart) / requestedFrequencySpan * (plotW - 1));
                                currentX = Math.max(frequencyLeft, Math.min(frequencyLeft + plotW - 1, currentX));
                                int currentY = y1 - (int) Math.round(spectrum[bin] * (y1 - y0));
                                currentY = Math.max(y0 + 1, Math.min(y1 - 1, currentY));
                                if (bin > firstBin) drawLine(pixels, w, previousSpectrumX,
                                        previousSpectrumY, currentX, currentY, channelColor(ch));
                                previousSpectrumX = currentX;
                                previousSpectrumY = currentY;
                            }
                        }
                        if (!requestedPsdVisible) continue;
                        double[] psd = calculatePsd(source, ch, requestedStart, requestedLength, 512, 4);
                        renderedPsds[ch] = psd;
                        int firstPsdBin = Math.max(0, (int) Math.floor(
                                requestedFrequencyStart * 2 * (psd.length - 1)));
                        int lastPsdBin = Math.min(psd.length - 1, (int) Math.ceil(
                                (requestedFrequencyStart + requestedFrequencySpan) * 2 * (psd.length - 1)));
                        int previousPsdX = psdLeft, previousPsdY = y1;
                        for (int bin = firstPsdBin; bin <= lastPsdBin; bin++) {
                            double binFrequency = bin * 0.5 / Math.max(1, psd.length - 1);
                            int currentX = psdLeft + (int) Math.round(
                                    (binFrequency - requestedFrequencyStart) / requestedFrequencySpan * (plotW - 1));
                            currentX = Math.max(psdLeft, Math.min(psdLeft + plotW - 1, currentX));
                            int currentY = y1 - (int) Math.round(psd[bin] * (y1 - y0));
                            currentY = Math.max(y0 + 1, Math.min(y1 - 1, currentY));
                            if (bin > firstPsdBin) drawLine(pixels, w, previousPsdX, previousPsdY,
                                    currentX, currentY, channelColor(ch));
                            previousPsdX = currentX;
                            previousPsdY = currentY;
                        }
                    }
                    return new RenderResult(target, (System.nanoTime() - began) / 1_000_000,
                            renderedSpectra, renderedPsds);
                }
                @Override protected void done() {
                    if (isCancelled()) return;
                    try {
                        RenderResult r = get(); image = r.image;
                        spectrumCache = r.spectra;
                        psdCache = r.psds;
                        status = String.format("显示 %,d–%,d（%,d 点/通道） | 折线渲染 %d ms | 左键标注，右键删除",
                                requestedStart, requestedStart + requestedLength - 1, requestedLength, r.millis);
                        repaint();
                    } catch (CancellationException ignored) {
                    } catch (Exception ex) { status = "渲染失败: " + ex.getMessage(); repaint(); }
                }
            };
            worker.execute();
        }

        private static int valueToY(float value, int middle, int bandHeight) {
            return middle - Math.round(Math.max(-1, Math.min(1, value)) * bandHeight * 0.42f);
        }

        static int channelColor(int channel) {
            Color c = Color.getHSBColor((channel * 0.083f + 0.46f) % 1f, 0.68f, 0.9f);
            return c.getRGB() & 0xFFFFFF;
        }

        private static void drawVertical(int[] p, int stride, int x, int y0, int y1, int color) {
            for (int y = y0; y <= y1; y++) p[y * stride + x] = color;
        }

        private static void drawLine(int[] pixels, int stride, int x0, int y0, int x1, int y1, int color) {
            int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
            int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
            int error = dx + dy;
            while (true) {
                pixels[y0 * stride + x0] = color;
                if (x0 == x1 && y0 == y1) return;
                int twice = error * 2;
                if (twice >= dy) { error += dy; x0 += sx; }
                if (twice <= dx) { error += dx; y0 += sy; }
            }
        }

        /** Returns a normalized, one-sided spectrum where 0=-100 dB and 1=0 dB. */
        private static double[] calculateSpectrum(WaveformDataInput source, int channel, long start,
                                                  long length, int requestedSize) {
            int n = requestedSize;
            while (n > length && n > 16) n >>= 1;
            double[] real = new double[n], imaginary = new double[n];
            for (int i = 0; i < n; i++) {
                long sample = start + Math.round(i * (length - 1.0) / Math.max(1, n - 1));
                double window = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / Math.max(1, n - 1));
                real[i] = source.valueAt(channel, Math.min(source.sampleCount() - 1, sample)) * window;
            }
            fft(real, imaginary);
            double[] magnitude = new double[n / 2 + 1];
            double max = 1e-12;
            for (int i = 0; i < magnitude.length; i++) {
                magnitude[i] = Math.hypot(real[i], imaginary[i]);
                max = Math.max(max, magnitude[i]);
            }
            for (int i = 0; i < magnitude.length; i++) {
                double db = 20 * Math.log10(Math.max(1e-12, magnitude[i] / max));
                magnitude[i] = Math.max(0, Math.min(1, (db + 100) / 100));
            }
            return magnitude;
        }

        /** Welch PSD estimate, normalized to a visible -100..0 dB/Hz range. */
        private static double[] calculatePsd(WaveformDataInput source, int channel, long start,
                                             long length, int requestedSize, int segmentCount) {
            int n = requestedSize;
            while (n > length && n > 16) n >>= 1;
            double[] power = new double[n / 2 + 1];
            for (int segment = 0; segment < segmentCount; segment++) {
                double[] real = new double[n], imaginary = new double[n];
                double segmentStart = segment * length / (double) segmentCount;
                double segmentLength = length / (double) segmentCount;
                for (int i = 0; i < n; i++) {
                    long sample = start + Math.round(segmentStart + i * Math.max(1, segmentLength - 1)
                            / Math.max(1, n - 1));
                    sample = Math.min(source.sampleCount() - 1, sample);
                    double window = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / Math.max(1, n - 1));
                    real[i] = source.valueAt(channel, sample) * window;
                }
                fft(real, imaginary);
                for (int bin = 0; bin < power.length; bin++) {
                    power[bin] += real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
                }
            }
            double max = 1e-24;
            for (double value : power) max = Math.max(max, value / segmentCount);
            for (int bin = 0; bin < power.length; bin++) {
                double dbPerHz = 10 * Math.log10(Math.max(1e-24, power[bin] / segmentCount / max));
                power[bin] = Math.max(0, Math.min(1, (dbPerHz + 100) / 100));
            }
            return power;
        }

        private static void fft(double[] real, double[] imaginary) {
            int n = real.length;
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) j ^= bit;
                j ^= bit;
                if (i < j) {
                    double r = real[i]; real[i] = real[j]; real[j] = r;
                    double im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im;
                }
            }
            for (int length = 2; length <= n; length <<= 1) {
                double angle = -2 * Math.PI / length;
                for (int base = 0; base < n; base += length) {
                    for (int k = 0; k < length / 2; k++) {
                        double cos = Math.cos(angle * k), sin = Math.sin(angle * k);
                        int even = base + k, odd = even + length / 2;
                        double tr = real[odd] * cos - imaginary[odd] * sin;
                        double ti = real[odd] * sin + imaginary[odd] * cos;
                        real[odd] = real[even] - tr; imaginary[odd] = imaginary[even] - ti;
                        real[even] += tr; imaginary[even] += ti;
                    }
                }
            }
        }

        private static void drawPoint(int[] pixels, int stride, int x, int y, int width,
                                      int minY, int maxY, int color) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    if (dx * dx + dy * dy > 4) continue;
                    int px = x + dx, py = y + dy;
                    if (px >= LEFT && px < width && py >= minY && py <= maxY) {
                        pixels[py * stride + px] = color;
                    }
                }
            }
        }

        private static void drawHorizontal(int[] p, int stride, int x0, int x1, int y, int color) {
            java.util.Arrays.fill(p, y * stride + x0, y * stride + x1, color);
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image != null) g.drawImage(image, 0, 0, null);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setFont(getFont().deriveFont(11f));
            int plotH = getHeight() - TOP - BOTTOM;
            int timeWidth = timePlotWidth();
            int timeRight = LEFT + timeWidth;
            int frequencyLeft = frequencyPlotLeft();
            int frequencyRight = frequencyPlotRight();
            int psdLeft = psdPlotLeft();
            int psdRight = psdPlotRight();
            for (int ch = 0; ch < source.channelCount(); ch++) {
                int bandTop = TOP + ch * plotH / source.channelCount();
                int bandBottom = TOP + (ch + 1) * plotH / source.channelCount();
                int axisBottom = bandBottom - 13;
                int mid = (bandTop + 2 + axisBottom) / 2;
                g2.setColor(new Color(170, 180, 198));
                g2.drawString("CH" + (ch + 1), 8, mid + 4);
                g2.setColor(new Color(105, 116, 135));
                g2.drawLine(LEFT, bandTop + 2, LEFT, axisBottom);
                g2.drawLine(LEFT, axisBottom, timeRight, axisBottom);
                if (frequencyVisible) {
                    g2.drawLine(frequencyLeft, bandTop + 2, frequencyLeft, axisBottom);
                    g2.drawLine(frequencyLeft, axisBottom, frequencyRight, axisBottom);
                }
                if (psdVisible) {
                    g2.drawLine(psdLeft, bandTop + 2, psdLeft, axisBottom);
                    g2.drawLine(psdLeft, axisBottom, psdRight, axisBottom);
                }
                // Detailed time-domain Y axis: values use the same mapping as the waveform.
                for (int tick = 0; tick <= 4; tick++) {
                    float value = 1.0f - tick * 0.5f;
                    int y = valueToY(value, mid, axisBottom - bandTop - 2);
                    g2.setColor(new Color(48, 58, 73));
                    g2.drawLine(LEFT + 1, y, timeRight, y);
                    g2.setColor(new Color(130, 142, 161));
                    g2.drawLine(LEFT - 4, y, LEFT, y);
                    String valueLabel = String.format("%+.2f", value);
                    g2.drawString(valueLabel, LEFT - g2.getFontMetrics().stringWidth(valueLabel) - 6, y + 4);
                }
                // Detailed frequency-domain Y axis: normalized magnitude in decibels.
                for (int tick = 0; tick <= 5; tick++) {
                    int db = -tick * 20;
                    int y = bandTop + 2 + tick * (axisBottom - bandTop - 2) / 5;
                    if (frequencyVisible) {
                        g2.setColor(new Color(48, 58, 73));
                        g2.drawLine(frequencyLeft + 1, y, frequencyRight, y);
                        g2.setColor(new Color(130, 142, 161));
                        g2.drawLine(frequencyLeft - 4, y, frequencyLeft, y);
                        String dbLabel = db + " dB";
                        g2.drawString(dbLabel, frequencyLeft - g2.getFontMetrics().stringWidth(dbLabel) - 6, y + 4);
                    }
                    if (psdVisible) {
                        g2.setColor(new Color(48, 58, 73));
                        g2.drawLine(psdLeft + 1, y, psdRight, y);
                        g2.setColor(new Color(130, 142, 161));
                        g2.drawLine(psdLeft - 4, y, psdLeft, y);
                        String psdLabel = db + " dB/Hz";
                        g2.drawString(psdLabel, psdLeft - g2.getFontMetrics().stringWidth(psdLabel) - 6, y + 4);
                    }
                }
                g2.setColor(new Color(105, 116, 135));
                for (int tick = 0; tick <= 4; tick++) {
                    int x = LEFT + tick * timeWidth / 4;
                    long sample = viewStart + tick * viewLength / 4;
                    g2.drawLine(x, axisBottom, x, axisBottom + 3);
                    String label = compactNumber(sample);
                    int labelX = Math.max(LEFT, Math.min(getWidth() - 40, x - g2.getFontMetrics().stringWidth(label) / 2));
                    g2.drawString(label, labelX, bandBottom - 2);
                    String frequencyLabel = String.format("%.4f", frequencyStart + tick * frequencySpan / 4);
                    if (frequencyVisible) {
                        int fx = frequencyLeft + tick * Math.max(1, frequencyRight - frequencyLeft) / 4;
                        g2.drawLine(fx, axisBottom, fx, axisBottom + 3);
                        g2.drawString(frequencyLabel, Math.min(frequencyRight - 25, fx - 10), bandBottom - 2);
                    }
                    if (psdVisible) {
                        int psdX = psdLeft + tick * Math.max(1, psdRight - psdLeft) / 4;
                        g2.drawLine(psdX, axisBottom, psdX, axisBottom + 3);
                        g2.drawString(frequencyLabel, Math.min(psdRight - 25, psdX - 10), bandBottom - 2);
                    }
                }
                String legend = "CH" + (ch + 1) + " 时域";
                int legendWidth = g2.getFontMetrics().stringWidth(legend);
                int legendTextX = timeRight - legendWidth - 7;
                int legendY = bandTop + 13;
                g2.setColor(new Color(channelColor(ch)));
                g2.drawLine(legendTextX - 25, legendY - 4, legendTextX - 5, legendY - 4);
                g2.fillOval(legendTextX - 17, legendY - 7, 6, 6);
                g2.drawString(legend, legendTextX, legendY);
                if (frequencyVisible) {
                    String frequencyLegend = "CH" + (ch + 1) + " FFT";
                    int frequencyLegendX = frequencyRight - g2.getFontMetrics().stringWidth(frequencyLegend) - 7;
                    g2.drawLine(frequencyLegendX - 25, legendY - 4, frequencyLegendX - 5, legendY - 4);
                    g2.fillOval(frequencyLegendX - 17, legendY - 7, 6, 6);
                    g2.drawString(frequencyLegend, frequencyLegendX, legendY);
                }
                if (psdVisible) {
                    String psdLegend = "CH" + (ch + 1) + " PSD";
                    int psdLegendX = psdRight - g2.getFontMetrics().stringWidth(psdLegend) - 7;
                    g2.drawLine(psdLegendX - 25, legendY - 4, psdLegendX - 5, legendY - 4);
                    g2.fillOval(psdLegendX - 17, legendY - 7, 6, 6);
                    g2.drawString(psdLegend, psdLegendX, legendY);
                }
            }
            List<List<Rectangle>> occupiedLabels = new ArrayList<>(source.channelCount());
            List<List<Rectangle>> occupiedFftLabels = new ArrayList<>(source.channelCount());
            List<List<Rectangle>> occupiedPsdLabels = new ArrayList<>(source.channelCount());
            for (int ch = 0; ch < source.channelCount(); ch++) {
                occupiedLabels.add(new ArrayList<>());
                occupiedFftLabels.add(new ArrayList<>());
                occupiedPsdLabels.add(new ArrayList<>());
            }
            for (Annotation a : annotations) {
                if (a.sample < viewStart || a.sample >= viewStart + viewLength) continue;
                int x = LEFT + (int) ((a.sample - viewStart) * timeWidth / (double) viewLength);
                for (int ch = 0; ch < source.channelCount(); ch++) {
                    int y0 = TOP + ch * plotH / source.channelCount();
                    int y1 = TOP + (ch + 1) * plotH / source.channelCount();
                    int axisBottom = y1 - 13;
                    float value = source.valueAt(ch, a.sample);
                    int markerY = valueToY(value, (y0 + 2 + axisBottom) / 2,
                            axisBottom - y0 - 2);
                    g2.setColor(new Color(255, 190, 70, 135));
                    g2.drawLine(x, y0 + 1, x, axisBottom);
                    g2.setColor(new Color(255, 205, 85));
                    g2.fillOval(x - 3, markerY - 3, 7, 7);
                    String text = a.text + "  Y=" + String.format("%.5f", value);
                    Rectangle labelBounds = findFreeLabelBounds(g2, text, x, markerY, y0 + 1, axisBottom - 1,
                            LEFT, timeRight, occupiedLabels.get(ch));
                    if (labelBounds != null) {
                        occupiedLabels.get(ch).add(labelBounds);
                        int targetX = labelBounds.x > x ? labelBounds.x : labelBounds.x + labelBounds.width;
                        int targetY = labelBounds.y + labelBounds.height / 2;
                        g2.setColor(new Color(255, 190, 70, 150));
                        g2.drawLine(x, markerY, targetX, targetY);
                        g2.setColor(new Color(255, 205, 85));
                        g2.drawString(text, labelBounds.x + 2,
                                labelBounds.y + g2.getFontMetrics().getAscent() + 1);
                    }
                }
            }
            if (spectrumCache != null && psdCache != null) {
                for (SpectralAnnotation annotation : spectralAnnotations) {
                    if ((annotation.psd && !psdVisible) || (!annotation.psd && !frequencyVisible)) continue;
                    if (annotation.frequency < frequencyStart
                            || annotation.frequency > frequencyStart + frequencySpan) continue;
                    int plotLeft = annotation.psd ? psdLeft : frequencyLeft;
                    int plotRight = annotation.psd ? psdRight : frequencyRight;
                    int x = plotLeft + (int) Math.round((annotation.frequency - frequencyStart)
                            / frequencySpan * (plotRight - plotLeft));
                    for (int ch = 0; ch < source.channelCount(); ch++) {
                        double[] values = annotation.psd ? psdCache[ch] : spectrumCache[ch];
                        double normalized = spectralValueAt(values, annotation.frequency);
                        double db = normalized * 100.0 - 100.0;
                        int y0 = TOP + ch * plotH / source.channelCount();
                        int y1 = TOP + (ch + 1) * plotH / source.channelCount();
                        int axisBottom = y1 - 13;
                        int markerY = axisBottom - (int) Math.round(normalized * (axisBottom - y0 - 2));
                        markerY = Math.max(y0 + 2, Math.min(axisBottom, markerY));
                        g2.setColor(new Color(255, 190, 70, 135));
                        g2.drawLine(x, y0 + 1, x, axisBottom);
                        g2.setColor(new Color(255, 205, 85));
                        g2.fillOval(x - 3, markerY - 3, 7, 7);
                        String unit = annotation.psd ? " dB/Hz" : " dB";
                        String text = String.format("F=%.6f  Y=%.2f%s", annotation.frequency, db, unit);
                        List<Rectangle> occupied = annotation.psd
                                ? occupiedPsdLabels.get(ch) : occupiedFftLabels.get(ch);
                        Rectangle bounds = findFreeLabelBounds(g2, text, x, markerY, y0 + 1,
                                axisBottom - 1, plotLeft, plotRight, occupied);
                        if (bounds != null) {
                            occupied.add(bounds);
                            int targetX = bounds.x > x ? bounds.x : bounds.x + bounds.width;
                            int targetY = bounds.y + bounds.height / 2;
                            g2.setColor(new Color(255, 190, 70, 150));
                            g2.drawLine(x, markerY, targetX, targetY);
                            g2.setColor(new Color(255, 205, 85));
                            g2.drawString(text, bounds.x + 2,
                                    bounds.y + g2.getFontMetrics().getAscent() + 1);
                        }
                    }
                }
            }
            if (selectionStart != null && selectionEnd != null && selectionDragged) {
                int x = Math.min(selectionStart.x, selectionEnd.x);
                int width = Math.abs(selectionEnd.x - selectionStart.x);
                g2.setColor(new Color(70, 145, 255, 50));
                g2.fillRect(x, TOP, width, Math.max(1, getHeight() - TOP - BOTTOM));
                g2.setColor(new Color(100, 175, 255));
                g2.drawRect(x, TOP, width, Math.max(1, getHeight() - TOP - BOTTOM));
            }
            g2.setColor(new Color(225, 230, 240));
            g2.drawString(status, LEFT, getHeight() - 9);
            g2.dispose();
        }

        private static String compactNumber(long value) {
            if (value >= 1_000_000) return String.format("%.2fM", value / 1_000_000.0);
            if (value >= 1_000) return String.format("%.1fk", value / 1_000.0);
            return Long.toString(value);
        }

        private static double spectralValueAt(double[] values, double frequency) {
            if (values == null || values.length == 0) return 0;
            double index = Math.max(0, Math.min(values.length - 1,
                    frequency / 0.5 * (values.length - 1)));
            int lower = (int) Math.floor(index);
            int upper = Math.min(values.length - 1, lower + 1);
            double fraction = index - lower;
            return values[lower] * (1 - fraction) + values[upper] * fraction;
        }

        /** Finds a label position that stays inside its channel and intersects no earlier label. */
        private static Rectangle findFreeLabelBounds(Graphics2D g2, String text, int pointX, int pointY,
                                                       int top, int bottom, int plotLeft, int plotRight,
                                                       List<Rectangle> occupied) {
            FontMetrics fm = g2.getFontMetrics();
            int width = fm.stringWidth(text) + 4;
            int height = fm.getHeight() + 2;
            int minX = plotLeft + 2;
            int maxX = plotRight;
            int[] xs = {pointX + 8, pointX - width - 8};
            int[] preferredYs = {pointY - height - 4, pointY + 4};
            for (int preferredY : preferredYs) {
                for (int x : xs) {
                    int boundedX = Math.max(minX, Math.min(maxX - width - 2, x));
                    int boundedY = Math.max(top, Math.min(bottom - height, preferredY));
                    Rectangle candidate = new Rectangle(boundedX, boundedY, width, height);
                    if (occupied.stream().noneMatch(candidate::intersects)) return candidate;
                }
            }
            // Search rows across the channel when all positions near the selected point are occupied.
            for (int y = top; y + height <= bottom; y += height + 2) {
                for (int x : xs) {
                    int boundedX = Math.max(minX, Math.min(maxX - width - 2, x));
                    Rectangle candidate = new Rectangle(boundedX, y, width, height);
                    if (occupied.stream().noneMatch(candidate::intersects)) return candidate;
                }
            }
            return null; // Keep the point marker, but never overlap text when the channel has no free space.
        }
    }

}
