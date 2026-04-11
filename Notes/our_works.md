摘要
随着移动设备的普及，以触控与视觉反馈为主导的人机交互方式已成为主流。然而，在用户难以持续注视屏幕或不便进行精细操作的场景中，该类交互模式的适用性受到制约。针对这一问题，本文提出一种基于声学感知的空间语义输入方法：利用智能手机内置双麦克风采集桌面敲击信号，在无需视觉依赖的条件下实现对敲击方向的判别，从而为低注意力交互提供一种可行路径。
围绕敲击事件的可靠检测与时延估计问题，本文首先分析桌面敲击信号的瞬态特性，并在带通预处理与包络表征基础上构建多约束融合检测框架。通过对候选峰对施加幅值、局部信噪比、瞬态特征、双通道一致性及峰形结构等约束，实现对有效双击事件的稳定提取。在此基础上，采用广义互相关-相位变换（GCC-PHAT）进行到达时间差估计，并结合麦克风几何先验约束搜索范围，同时利用亚采样插值对相关峰进行细化。针对反射与混响引起的相关峰展宽问题，引入基于短时片段的相关计算与多子带融合策略，并结合峰形一致性对估计结果进行加权，从而获得更稳健的时延表征。
在空间语义映射阶段，本文引入用户标定机制，将时延、通道能量差异及相关峰特征构造为特征向量，并通过分层判别策略实现从连续声学表征到离散扇区语义的映射。个体化建模基于各类别样本的均值与对角协方差，并采用马氏距离度量样本偏离程度，以增强类内刻画与异常拒识能力；在样本不足时，采用 K 近邻方法作为补充。决策层进一步融合短时多次判别结果，并构建由多因素组成的综合置信度指标，结合类别间隔与峰形质量等规则实现拒识，以降低误触发率。
实验结果表明，在典型桌面环境下，所提方法能够实现稳定的三扇区识别，并表现出一定的噪声鲁棒性。该研究为基于日常移动终端的无视觉依赖交互提供了一种可行实现路径。
关键词：人机交互；空间语义；声学感知；GCC-PHAT；用户标定；低注意力交互
 
Abstract:
With the widespread adoption of mobile devices, touch-based and visually guided interaction has become the dominant paradigm in human–computer interaction. However, such interaction is constrained in scenarios where users cannot continuously attend to the screen or perform fine-grained operations. To address this limitation, this paper proposes an acoustic sensing-based spatial semantic input method that leverages the dual microphones of a smartphone to capture tabletop tapping signals and infer tapping direction without visual feedback, thereby enabling low-attention interaction.
To ensure reliable event detection and time-delay estimation, the transient characteristics of tapping signals are first analyzed, and a multi-constraint fusion framework is constructed based on band-pass preprocessing and envelope representation. Candidate peak pairs are filtered under constraints including amplitude, local signal-to-noise ratio, transient features, inter-channel consistency, and peak morphology, enabling robust extraction of valid double-tap events. Time-difference-of-arrival is then estimated using generalized cross-correlation with phase transform (GCC-PHAT), where the search range is constrained by microphone geometry and refined via sub-sample interpolation. To mitigate peak broadening caused by reflections and reverberation, short-time segment-based correlation and multi-band fusion are introduced, with peak-shape consistency incorporated to enhance robustness.
For spatial semantic mapping, a user calibration mechanism is adopted. Feature vectors are constructed from time delay, inter-channel energy differences, and correlation peak characteristics, and a staged decision strategy maps continuous acoustic representations to discrete sector labels. Personalized modeling is based on class-wise means and diagonal covariances, with Mahalanobis distance used for discrimination and outlier rejection; k-nearest neighbors (KNN) is employed as a fallback when data are insufficient. At the decision level, multiple short-term predictions are fused, and a composite confidence score is constructed by integrating multiple factors, combined with class margin and peak quality constraints to suppress false triggers.
Experimental results demonstrate that the proposed method achieves stable three-sector recognition in typical tabletop environments with reasonable robustness to noise. This work provides a practical approach for vision-independent interaction on commodity mobile devices.
Key words: Human-Computer Interaction; Spatial Semantics; Acoustic Sensing; GCC-PHAT; User Calibration; Low-Attention Interaction
 
引言
1.1 研究背景
随着智能手机和移动计算设备的快速发展，人机交互方式逐渐以触控操作和图形界面为主。这类交互方式通常依赖用户的视觉注意力以及精细的手部操作，在大多数场景下能够提供良好的使用体验。然而，在部分实际应用场景中，例如用户双手被占用、无法持续关注屏幕或需要进行快速简单控制时，传统交互方式的使用会受到一定限制。
因此，近年来研究者开始关注“低注意力交互”与“无视觉依赖交互”等新型交互方式，旨在降低用户对视觉与精细操作的依赖，使交互过程更加自然与高效。在这一背景下，利用环境本身作为输入媒介（如桌面、墙面等）进行交互，成为一种具有潜力的研究方向。
1.2 研究问题
基于环境的交互方式中，桌面敲击是一种自然且易于实现的输入形式。然而，如何利用普通设备准确感知敲击位置并将其转换为有效的交互指令，仍面临以下问题：
（1）桌面敲击信号易受环境噪声干扰，如何实现稳定的敲击检测；
（2）在仅使用手机双麦克风的条件下，声源方向信息有限，难以实现精确定位；
（3）连续声学特征与离散交互指令之间缺乏直接映射关系；
（4）在实际使用中，需要控制误触发，提高系统稳定性。
因此，如何在有限硬件条件下，实现稳定、可用的空间交互方法，是本文需要解决的核心问题。
1.3 本文工作
针对上述提出的研究问题，本文介于声学信号处理与人机交互系统设计之间，提出一种在常见硬件条件下基于声学感知的空间语义输入方法。该方法利用智能手机的双麦克风，通过分析桌面敲击产生的声学信号，实现对敲击方向的判别，并将其映射为离散交互指令。
本文的主要工作包括：
（1）设计基于多约束的敲击检测方法，提高在噪声环境下的事件识别能力；
（2）基于GCC-PHAT方法实现双通道时间差估计，并通过优化策略提升估计精度；
（3）提出基于用户标定的扇区划分方法，实现时间差信号向空间语义的映射；
（4）构建多因素融合的置信度评估机制，降低误触发率；
（5）实现原型系统并进行初步实验验证其可行性。
1.4 论文结构
本文结构安排如下。
第1章介绍研究背景、研究问题及本文的主要工作；
第2章介绍与本文相关的声学定位与人机交互技术基础；
第3章给出系统整体架构，描述从敲击感知到语义输出的处理流程；
第4章重点阐述关键方法，包括敲击检测、时延估计及扇区语义映射与置信度建模；
第5章通过实验对所提方法进行验证，并分析其性能表现；
第6章总结全文并展望未来工作。
