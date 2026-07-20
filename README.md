# Swing 20 通道超大规模时域波形图

纯 Java Swing 示例：20 个同步时域图、FFT 频域图及功率谱密度图，每通道逻辑上包含 25,000,000 个时间有序采样点。每行依次为时域、当前视野数据经 Hann 窗和 FFT 得到的单边幅度谱、采用 Welch 分段平均得到的 PSD。时域纵轴显示 ±1 范围内的 0.5 间隔刻度，FFT纵轴显示相对 dB，PSD纵轴显示相对 dB/Hz。

## 运行

需要 JDK 17+：

```powershell
mvn compile
java -cp target/classes layout.MultiChannelWaveform
```

也可不使用 Maven：

```powershell
javac -encoding UTF-8 -d out src/main/java/analysis/WaveformDataInput.java src/main/java/layout/MultiChannelWaveform.java
java -cp out layout.MultiChannelWaveform
```

## 构建 Linux 可执行程序

安装并启动 Docker Desktop，然后在项目目录执行：

```powershell
.\build-linux.ps1
```

构建结果位于 `dist-linux`。将整个目录复制到 x86-64 Linux，直接运行：

```bash
chmod +x bin/waveform-viewer
./bin/waveform-viewer
```

应用已经包含裁剪后的 Linux Java 运行时，不需要在目标机器安装 Java，也不通过 `java -jar` 启动。不要只复制 `bin/waveform-viewer`，它依赖同目录中的 `lib` 运行时文件。

屏幕可视区固定显示 5 路，通过垂直滚动条或鼠标滚轮查看其余通道。顶部右侧展示20路颜色图例，左侧提供缩放后退、缩放前进和拖拽模式按钮。每个通道都有独立的横纵坐标轴。`Ctrl + 滚轮`同步缩放；在任意通道左键单击，会在同一横坐标处标注全部 20 路的实际波形点，并分别显示各通道的 X/Y 数值，同一采样点不会重复标注。标注文字会自动避让，空间不足时保留点标记而不重叠文字。左键拖动圈选范围并放大；开启拖拽模式后左键用于同步平移，未开启时也可用 `Shift + 左键` 或鼠标中键平移；右键删除整组同步标注。

## 性能策略

- 不创建采样点对象，避免 5 亿对象带来的内存和 GC 压力。
- 后台 `SwingWorker` 渲染，不阻塞 Swing EDT。
- 按屏幕分辨率抽取时间序列采样点，并把相邻点连接成连续折线；放大后每个原始采样点只占一个 X 位置并显示点标记，不会因重复采样扩展成横线。
- 20 个通道共享 `viewStart/viewLength`，天然保持缩放和平移同步。
- 直接操作 `BufferedImage` 的 `int[]` 像素缓冲。
- 窗口变化、缩放和平移时取消已经过期的渲染任务。

示例数据源是虚拟数据。接入真实数据时实现 `WaveformDataSource`；生产环境建议为原始二进制数据建立多级 min/max 索引，使任意缩放级别的渲染复杂度保持在 `通道数 × 屏幕宽度`。
