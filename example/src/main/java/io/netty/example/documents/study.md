#### NIO核心组件
  - Channel
  - Buffer
  - Selector

#### NIO vs IO
  - IO基于流(Stream oriented) NIO基于Buffer(Buffer oriented)
  - IO操作是阻塞的 NIO操作是非阻塞的
  - IO没有Selector概念 NIO有Selector概念

#### IO基于Stream vs NIO基于Buffer
  - 传统的IO是面向字节流或字符流
  - NIO引入了Channel和Buffer 从Channel中读取数据到Buffer中或者将Buffer中的数据写入到Channel
  - 基于流的IO操作以流式的方式顺序地从一个Stream中读取一个或多个字节 不能随意改变读取指针的位置
  - 基于Buffer需要从Channel中读取数据到Buffer中 当Buffer中有数据后 就可以对这些数据进行操作 不像IO那样是顺序操作 NIO中可以随意地读取任意位置的数据

#### 阻塞 vs 非阻塞
  - java中各种Stream的操作都是阻塞的
    - 调用read方法读取一个文件的内容 调用read的线程会被阻塞住 知道read操作完成
  - NIO的非阻塞模式允许非阻塞地进行IO操作
    - 在NIO的非阻塞模式中 调用read方法 不会阻塞当前线程
      - 如果此时有数据 则read读取并返回
      - 如果此时没有数据 则read直接返回

#### Selector
  - Selector是NIO中才有的概念 是java NIO之所以可以非阻塞地进行IO操作的关键
  - 通过Selector 一个线程可以监听多个Channel的IO事件
    - 向一个Selector中注册了Channel后 Selector内部机制可以自动不断地查询(select)这些注册的Channel是否有已经就绪的IO事件(可读、可写、网络连接完成)
    - 通过Selector机制 可以很简单地使用一个线程高效地管理多个Channel
