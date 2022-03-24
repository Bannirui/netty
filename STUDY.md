# study

### 服务端启动

* [X]  服务端初始化
* [X]  NioServerSocketChannel创建
* [X]  服务端Channel初始化
* [X]  注册多路复用
* [X]  绑定端口

### NioEventLoop

* [X]  NioEventLoopGroup创建线程执行器
* [ ]  NioEventLoopGroup创建NioEventLoop
* [ ]  初始化线程选择器
* [ ]  NioEventLoop线程启动
* [ ]  优化selector
* [ ]  执行select操作
* [ ]  处理IO事件
* [ ]  执行任务队列

### 客户端接入流程

* [ ]  初始化NioSocketChannelConfig
* [ ]  处理接入事件handler的创建
* [ ]  NioSocketChannel的创建
* [ ]  NioSocketChannel注册到selector
* [ ]  监听读写事件

### pipeline

* [ ]  pipeline创建
* [ ]  pipeline添加
* [ ]  pipeline删除
* [ ]  传播inbound事件
* [ ]  传播outbound事件
* [ ]  传播异常事件

### ByteBuf

* [ ]  AbstractByteBuf
* [ ]  ByteBuf分类
* [ ]  缓冲区分配器
* [ ]  PooledByteBufAllocator
* [ ]  directArena分配缓冲区
* [ ]  命中缓存
* [ ]  page级别的内存分配
* [ ]  Subpage级别的内存分配
* [ ]  ByteBuf回收
* [ ]  SocketChannel读取数据的过程

### 解码器

* [ ]  ByteToMessageDecoder
* [ ]  固定长度解码器
* [ ]  行解码器
* [ ]  分隔符解码器

### 编码器和写数据

* [ ]  writeAndFlush事件传播
* [ ]  MessageToByteEncoder
* [ ]  写buffer队列
* [ ]  刷新buffer队列
* [ ]  Future和Promise
