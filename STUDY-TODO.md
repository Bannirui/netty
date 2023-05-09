## TODO LIST

record dynamic item for study

- [X] 0x01.NioEventLoopGroup和NioEventLoop的父子组合关系是怎么编排的
- [ ] 0x02.很明显NioEventLoopGroup具备任务管理能力和调度能力
  * 普通任务存放在哪儿
  * 定时任务存放在哪儿
  * IO读写(特指Socket编程)的主动权肯定在OS的多路复用器上，怎么转移对IO任务的控制权的
- [ ] 0x03.同上，NioEventLoop是具体的执行者
  * 普通任务怎么执行
  * 定时任务怎么执行
  * IO任务怎么执行
- [ ] 0x04.NioEventLoop是单线程的模型，但是它的继承关系图上没有Thread，那么跟线程就不是派生关系，那就只剩组合关系了，怎么组合线程的(偏软件架构模型)
- [ ] 0x05.NioEventLoop的线程模型是怎样的(线程执行模型)
- [ ] 0x06.用Netty都是进行网络编程的，既然这样，为什么作者设计的NioEventLoop是从Executor->EventLoop->NioEventLoop，为什么不是直接定义一个NioEventLoop，也就是说为什么NioEventLoop的实现要关注跟IO没关系的任务
- [ ] 0x07.NioEventLoopGroup派生自EventLoopGroup，也就是从EventLoopGroup开始，整个组件体系跟Channel开始扯上关系，那么存在多个NioEventLoopGroup时，是怎么实现类似于dispatch的功能的，继而，Netty是如何实现Reactor事件模型的
- [ ] 0x08.Java中的线程到底是啥