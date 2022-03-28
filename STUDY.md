# study

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

* [ ]  writeAndFlush事件传播
* [ ]  MessageToByteEncoder
* [ ]  写buffer队列
* [ ]  刷新buffer队列
* [ ]  Future和Promise
