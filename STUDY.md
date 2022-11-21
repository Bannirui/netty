# study

## 笔记

- [Netty源码-00-启动](https://github.com/Bannirui/blogs/blob/master/netty/Netty%E6%BA%90%E7%A0%81-00-%E5%90%AF%E5%8A%A8.md)
- [Netty源码-01-NioEventLoopGroup](https://github.com/Bannirui/blogs/blob/master/netty/Netty%E6%BA%90%E7%A0%81-01-NioEventLoopGroup.md)
- [Netty源码-02-FastThreadLocalThread](https://github.com/Bannirui/blogs/blob/master/netty/Netty%E6%BA%90%E7%A0%81-02-FastThreadLocalThread.md)
- [Netty源码-03-NioEventLoop](https://github.com/Bannirui/blogs/blob/master/netty/Netty%E6%BA%90%E7%A0%81-03-NioEventLoop.md)
- [Netty源码-04-Selector](https://github.com/Bannirui/blogs/blob/master/netty/Netty%E6%BA%90%E7%A0%81-04-Selector%E5%A4%9A%E8%B7%AF%E5%A4%8D%E7%94%A8%E5%99%A8%E4%BC%98%E5%8C%96.md)
- [Netty源码-05-EventLoop](https://github.com/Bannirui/blogs/blob/master/netty/Netty%E6%BA%90%E7%A0%81-05-EventLoop.md)
- [Netty源码-06-MpscQueue](https://github.com/Bannirui/blogs/blob/master/netty/Netty%E6%BA%90%E7%A0%81-06-MpscQueue%E5%AE%9E%E7%8E%B0.md)
- [Netty源码-07-Channel](https://github.com/Bannirui/blogs/blob/master/netty/Netty%E6%BA%90%E7%A0%81-07-Channel.md)
- [Netty源码-08-ChannelInitializer](https://github.com/Bannirui/blogs/blob/master/netty/Netty%E6%BA%90%E7%A0%81-08-ChannelInitializer.md)
- [Netty源码-09-ServerBootstrapAcceptor](https://github.com/Bannirui/blogs/blob/master/netty/Netty%E6%BA%90%E7%A0%81-09-ServerBootstrapAcceptor.md)
- [Netty源码-10-ChannelFuture](https://github.com/Bannirui/blogs/blob/master/netty/Netty%E6%BA%90%E7%A0%81-10-ChannelFuture.md)
- [Netty源码-11-Pipeline](https://github.com/Bannirui/blogs/blob/master/netty/Netty%E6%BA%90%E7%A0%81-11-Pipeline.md)
- [Netty源码-12-bind流程](https://github.com/Bannirui/blogs/blob/master/netty/Netty%E6%BA%90%E7%A0%81-12-bind%E6%B5%81%E7%A8%8B.md)
- [Netty源码-13-connect流程](https://github.com/Bannirui/blogs/blob/master/netty/Netty%E6%BA%90%E7%A0%81-13-connect%E6%B5%81%E7%A8%8B.md)

## 学习目标

### 服务端启动

* [X]  服务端初始化
* [X]  NioServerSocketChannel创建
* [X]  服务端Channel初始化
* [X]  注册多路复用
* [X]  绑定端口

### NioEventLoop

* [X]  NioEventLoopGroup创建线程执行器
* [X]  NioEventLoopGroup创建NioEventLoop
* [X]  初始化线程选择器
* [X]  NioEventLoop线程启动
* [X]  优化selector
* [X]  执行select操作
* [X]  处理IO事件
* [X]  执行任务队列

### 客户端接入流程

* [X]  初始化NioSocketChannelConfig
* [X]  处理接入事件handler的创建
* [X]  NioSocketChannel的创建
* [X]  NioSocketChannel注册到selector
* [X]  监听读写事件

### pipeline

* [X]  pipeline创建
* [X]  handler添加
* [X]  handler删除
* [X]  传播inbound事件
* [X]  传播outbound事件
* [X]  传播异常事件

### ByteBuf

* [X]  AbstractByteBuf
* [X]  ByteBuf分类
* [X]  缓冲区分配器
* [ ]  PooledByteBufAllocator
* [ ]  directArena分配缓冲区
* [ ]  命中缓存
* [ ]  page级别的内存分配
* [ ]  Subpage级别的内存分配
* [ ]  ByteBuf回收
* [ ]  SocketChannel读取数据的过程

### 解码器

* [X]  ByteToMessageDecoder
* [X]  固定长度解码器
* [X]  行解码器
* [X]  分隔符解码器

### 编码器和写数据

* [X]  writeAndFlush事件传播
* [X]  MessageToByteEncoder
* [X]  写buffer队列
* [ ]  刷新buffer队列
* [ ]  Future和Promise
