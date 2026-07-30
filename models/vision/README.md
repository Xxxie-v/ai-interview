# SCRFD + ArcFace ONNX 模型目录

本目录只保存使用说明，不提交模型权重。

默认文件名：

- `det_10g.onnx`：SCRFD 人脸检测模型，需要输出边界框和五点关键点。
- `w600k_r50.onnx`：ArcFace 人脸特征模型，输入尺寸为 `112 x 112`。

启用方式：

```dotenv
APP_INTERVIEW_VISION_PROVIDER=onnx
APP_INTERVIEW_VISION_SCRFD_MODEL=models/vision/det_10g.onnx
APP_INTERVIEW_VISION_ARCFACE_MODEL=models/vision/w600k_r50.onnx
```

当前 SCRFD 解码器支持 InsightFace 常见的 6 输出（分类 + 边界框）和 9 输出
（分类 + 边界框 + 五点关键点）结构。ArcFace 身份基准在每场面试第一次检测到高置信度
单人脸时建立，仅保存在服务进程内，默认四小时过期，不写入数据库。

模型权重可能有单独的训练数据或商业使用许可，部署到企业环境前需要确认所选模型的授权。
