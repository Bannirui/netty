## TODO LIST

record dynamic item for study

- [X] 0x01.NioEventLoopGroup和NioEventLoop的父子组合关系是怎么编排的
- [X] 0x02.NioEventLoopGroup组件对客户端任务提交的响应
  * 普通任务存放在哪儿
  * 定时任务存放在哪儿
  * IO读写(特指Socket编程)的主动权肯定在OS的多路复用器上，怎么转移对IO任务的控制权的
- [X] 0x03.接上，NioEventLoop作为实际的任务管理者是如何进行管理\执行
  * 普通任务
  * 定时任务
  * IO任务
- [ ] 0x04.NioEventLoop是单线程的模型，但是它的继承关系图上没有Thread，那么跟线程就不是派生关系，那就只剩组合关系了，怎么组合线程的(偏软件架构模型)
- [ ] 0x05.NioEventLoop的线程模型是怎样的(线程执行模型)
- [ ] 0x06.用Netty都是进行网络编程的，既然这样，为什么作者设计的NioEventLoop是从Executor->EventLoop->NioEventLoop，为什么不是直接定义一个NioEventLoop，也就是说为什么NioEventLoop的实现要关注跟IO没关系的任务
- [ ] 0x07.NioEventLoopGroup派生自EventLoopGroup，也就是从EventLoopGroup开始，整个组件体系跟Channel开始扯上关系，那么存在多个NioEventLoopGroup时，是怎么实现类似于dispatch的功能的，继而，Netty是如何实现Reactor事件模型的
- [ ] 0x08.Java中的线程到底是啥
- [ ] 0x09.NioEventLoopGroup是如何面向客户端交互的
- [X] 0x0A.NioEventLoop是如何接收客户端提交的任务的
- [ ] 0x0B.Netty中Channel的register注册是什么语义 NioEventLoop::register
- [ ] 0x0C.Netty中实例化Thread时机是在任务被执行的时候
- [ ] 0x0D.NioEventLoop::run()执行IO任务
- [ ] 0x0E.NioEventLoop::taskQueue中任务的区别(如何区分定时任务一次性还是周期性)
- [ ] 0x0F.NioEventLoopGroup::next()
- [ ] 0x10.NioEventLoop这个组件的启动时机 Netty是事件驱动(整体的运转流程不需要外界干预是自发性根据事件状态驱动、初始事件是怎么生成的) 需要外界手动提交一个任务(作用是可以驱动事件模型转动的核心)
- [X] 0x11.回头关注NioEventLoopGroup和NioEventLoop的初始化详细过程