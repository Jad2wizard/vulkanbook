- 构建示例的jar包  
	`mvn package -pl chapter-XX`
- 运行示例  
	`cd chapter-XX/target/ && java -XstartOnFirstThread -jar chapter-XX-1.0.0.jar`

## java off-heap 堆外内存
1. java 中，JVM 内存指的是堆内存。在机器内存中，不属于堆内存的部分就是堆外内存。C语言中直接从机器内存中分配内存，与 java 的堆外内存和 JavaScript 中的 TypedArray 概念相似
2. java 中可以通过 Unsafe 和 nio 包下的 DirectByteBuffer 操作堆外内存
3. 通过 JVM 参数-XX:MaxDirectMemorySize=40M可以将最大堆外内存设置为40M，内存超出后会报错内存溢出
4. 为什么要使用堆外内存？
	1. 无法对 jvm 的堆内存做精细化布局，但本地库需要数据有明确的分布
	2. GC 可能在任意时间对堆内存中的对象或者数组进行移动，甚至是在本地库方法执行过程中
	3. 本地库使用的对外内存，如果在 java 中使用堆内存，那么数据在堆内存和堆外内存之间拷贝时会带来性能损失
## java.nio.ByteBuffer
1. lwjgl 使用 java.nio 的 DirectByteBuffer 发送数据到本地库以及接收本地库的数据.
2. ByteBuffer 中含有 mark, position, limit 和 capacity 四个指针
3. capacity 指针，指向 ByteBuffer 的末尾，调用 allocate 方法后改变
4. limit 指针，指向 ByteBuffer有效数据的范围上限。初始时指向 capacity。flip 方法会将 limit 指向 position 指针，clear 方法会将 limit 指向 capacity 指针。position指针超过 limit 之后会报内存错误
5. position 指针，指向当前 ByteBuffer 的读写位置。初始时指向 ByteBuffer 的起始位置。调用读写方法 get/put后，position 指针会前移一位，直到遇到 limit 指针。flip、rewind 和 clear 方法都会将 position 指针移动到 ByteBuffer 的起始位置。reset 方法会将 position 指针指向 mark 指针
6. mark 指针，指向position指针的前一个位置。初始时指向 ByteBuffer 的-1位置。mark 方法会将 mark 指针指向position 指针。flip、rewind 和 clear 方法会将 mark 指针指向-1位置。如果 mark 指针指向-1时调用 reset 方法会报错。
## lwjgl 内存分配
1. lwjgl 中 buffer 只允许在堆外内存上创建。因为堆内存上的数据在传给本地代码时需要进行拷贝，会带来性能损耗
#### stack allocation
1. 适用于容量小、生命周期短又需要频繁分配的内存分配需求。
2. 每个线程在 ThreadLocal 中都会持有一个 MemoryStack 实例。MemoryStack 实例创建时会通过 java.nio.ByteBuffer.allocateDirect分配一块64KB的堆外内存。
3. MemoryStack实现了AutoClosable 接口，配合 try-with-resource 语法，在离开 try-catch 块后的 close 方法中自动执行出栈操作
4. 入栈操作
	```java
	int vbo;
	try (MemoryStack stack = stackPush()) {
		IntBuffer ip = stack.callocInt(1);
		glGenBuffers(ip);
		vbo = ip.get(0);
	} 
	```
#### MemoryUtil
1. JNI 通过调用本地库完成内存的分配和释放
2. MemoryUtil 适合用于分配内存占用大或者生命周期长的数据
3. 用户需要自己手动释放内存
#### ByteBuffer.allocateDirect
1. 缺点是性能差、内存会被 GC 释放
  
## Chapter-02
1. MemoryStack.mallocXXX 不会直接分配内存，但 MemoryStack.callocXXX 会直接分配内存。vnb中使用的 calloc。
2. 本章中的 VkXXXInfo 对象的类都继承自 org.lwjgl.system.Struct 抽象类，Struct 中通过 ByteBuffer 类型的 container 变量存放 VkXXX 对象的数据，该数据需要传给本地代码。并通过 Pointer.Default.address记录 ByteBuffer 地址。所以创建 vk 对象时需要先创建 ByteBuffer
3. VkApplicationInfo, VkInstanceCreatInfo, VkExtensionProperties 和 VkLayerProperties 类型的对象通过 calloc(stack)创建， 这些对象直接在栈空间 stack 上分配内存，故 container 字段为 null
4. VkApplicationInfo 的 size 为48字节。字节对齐长度为8字节。
5. VkApplicationInfo 中包含 sType(4字节), pNext(8字节), pApplicationName(8字节), applicationVertsion(4字节), pEngineName(8字节), engineVersion(4字节), apiVersion(4字节)等几个字段，其中以 p 开头的字段是 C 语言中的指针。因为VkApplicationInfo 需要传给本地代码，所以不能用 java 对象来组织，需要写到 buffer 中。本地代码应该是根据 sType 来识别 buffer 中存放的是什么对象，然后从 buffer 中读取对象中的各个字段值。
6. VkApplicationInfo 类中通过几个字段的大写静态变量来记录各个字段在 buffer 中的偏移量。applicationName 以及 engineName 等文本信息通过 memoryStack.UTF8转换为 ByteBuffer，VkApplicationInfo 中再通过指针指向这些 ByteBuffer。所以不管文本长度如何，VkApplicationInfo 类型的对象在 buffer 中占据的空间是固定的
7. Instance 类中方法中的局部 vk对象都是在stack 上分配的内存，这些分配的内存会在方法内部的try-catch 语句退出时自动回收。Instance.debugUtils成员变量是在 Instance.createDebugCallBack 方法中创建的一个 VkDebugUtilsMessengerCreateInfoEXT类型的对象。debugUtils 在通过 calloc分配内存时没有传入 stack对象，故该对象的内存并非位于 stack 上，而是分配在堆外内存中（stack 本身也是分配在堆外内存，但可以被自动回收）。所以在 Instance.cleanup 方法中需要对 debugUtils 对象手动回收内存

## Chapter-03
### vulkanb.eng.graph.vk.PhysicalDevice 类
1. PhysicalDevice类代表一个物理设备例如 GPU。因为宿主机上可能存在多个物理设备，所以 PhysicalDevice 提供了一个静态方法 createPhysicalDevice 来罗列所有可用的GPU 并返回最合适的 GPU。
2. 从本地代码获取到的物理设备句柄被封装到了 lwjgl.vulkan.VkPhysicalDevice 类中，该类还通过 instance 字段指向了 VkInstance 实例。createPhysicalDevice 方法中直接返回了第一个GPU。lwjgl 中很多地方使用 PointerBuffer 存放指向某个本地 vk 对象的指针，然后将这些指针作为句柄传给 lwjgl 自身封装类的构造函数，作为 Pointer.Default 类的 address 字段值记录下来。这些句柄也就是指向本地代码生成的各个 vk 对象的内存地址指针
3. PhysicalDevice的所有 vkXXX 成员变量的内存都没有分配到 memoryStack 上，需要在 cleanup 方法中手动释放这些变量的内存
4. PhysicalDevice 中通过两次调用vkEnumeratePhysicalDevices方法拿到GPU的句柄，第一次先获取 GPU 数量，然后根据 GPU 数量创建句柄缓冲区(PointerBuffer)。第二次调用时将空的GPU 句柄缓冲区传入获取各个 GPU 的真实句柄
5. PhysicalDevice 中通过判断 GPU 是否支持显卡队列族（Queue Family）以及是否有能力将生成的图像输出到屏幕上来确定GPU 设备是否可用
6. PhysicalDevice.vkQueueFamilyProps是一个存放了当前设备支持的所有队列族的 buffer。Vulkan 中应用程序将各种 command buffer提交到不同的队列中，然后再由 GPU 异步从队列中获取 command 并执行。lwjgl 中列出的队列族共有
  ```java
  VK_QUEUE_GRAPHICS_BIT = 1; //图形操作队列
  VK_QUEUE_COMPUTE_BIT = 2; //计算操作队列
  VK_QUEUE_TRANSFER_BIT = 4; //传输操作队列
  VK_QUEUE_SPARSE_BINDING_BIT = 8; //稀疏绑定
  ```
  各种队列族中的队列只允许提交该队列族支持的队列操作的 command buffer
### vulkanb.eng.graph.vk.Device 类
1. Instance、PhysicalDevice 以及 LogicalDevice 的区别：PhysicalDevice 用来描述物理设备（GPU）的硬件特性、功能以及资源，比如 GPU 支持哪些队列族。开发者在 PhysicalDevice 的基础上创建 LogicalDevice，LogicalDevice 用于实际管理 GPU 资源，提供 GPU 的队列族、command buffer、调用以及与命令操作相关的功能。总之 LogicalDevice 时对 GPU 能力的一次封装。而VulkanInstance 则时 vulkan api的入口，用于管理整个 Vulkan 程序的全局状态。
2. 构造函数中先获取了GPU 支持的所有拓展，然后只 enable 了`KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME`拓展
3. 本章中没有 enable 任何 VkPhysicalDeviceFeature
4. 构造函数第三步时创建 queue 的 creationInfo。此处并没有创建 queue，只是在各个 queueCreationInfo 中记录了该 queue 的队列族索引以及队列的优先级，此处将所有队列的优先级都设置为了0.0。
5. 在创建 vkDevice 时会根据vkDeviceCreateInfo 中的 queueCreateInfo 信息来创建 queue。
### vulkanb.eng.graph.vk.Queue 类
1. 在创建LogicalDevice 时已经创建好了 vkQueue。构造函数中通过vkGetDeviceQueue 获得 LogicalDevice 中本地库的队列句柄

## Chapter-05 Clearing the screen
### vulkanb.eng.graph.vk.SwapChainRenderPass 类
1. render pass和 webGPU 中的 renderPass 概念类似
2. 创建 render pass 的结构体 `VkRenderPassCreateInfo` 需要设置 attachment、subpasses和 dependencies三种属性。总结发现在创建以 vkXXX 开头的vulkan 对象时，都需要先创建一个 XXXInfo 结尾的结构体，对此的理解应该是vulkan 对象是通过本地代码创建的，而普通的 java 对象无法传给本地代码。而 XXXInfo 结构体所属类中的字段值都以数值或者指针的形式存放在memoryStack(堆外内存)或直接存放在堆外内存上，这样可以直接被本地代码中的 vulkan 访问到。
3. attachments分为输入 attachment 和输出 attachment。输出attachment 是此次渲染输出的图像。而输入 attachment 不是纹理，而是之前渲染的输出attachment。attachment 对应于一个 VkImageView，可以是 color 或者 depth。本章中的 attachment 对应的是 swapChain 中的 image，只是在 swapChainRenderPass 中还未将二者关联起来
4. `VkAttachmentDescription.Buffer attachments` 是一个 Buffer。在 lwjgl.vulkan中，有些结构体类型带有一个内部类 Buffer，个人理解应该是针对诸如 attachment 这种有批量创建的资源时会使用到 Buffer。此处的VkXXX.Buffer 类型的对象等价于 VkXXX 数组。通过 Buffer.get(index)获取指定索引下的 VkXXX 结构体对象。可能是因为如果声明为 VkXXX[]数组的话，该对象是 java 数组，无法本 vulkan 本地代码直接访问。此处将 attachments.get(0)即第一个 attachments 设置为color attachment 并使用 swapChain.surfaceFormat.imageFormat 设置其 format，最终会将 renderPass 中的 attachments[0]和 swapChain 里的ImageView 通过 FrameBuffer 联系起来。 
5. 通过 `VkAttachmentDescription.colloc(1, stack)`，在stack 上创建了一个 attachment之后，在给 attachment 设置属性。需要设置的各种属性与 webGPU 中的 attachment 类似。创建的 attachment 还未链接到任何具体图像上去，只是包含一些设置和描述信息。
6. 创建 subPass 时，需要创建 `VkAttachmentReference` 结构体来引用attachment。并通过`VkAttachmentReference.attachment(index)` 来绑定 attachments buffer 中的第 index 个 attachment
7. 通过 `VkSubpassDescription.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)`设置创建的 subpass 用于图像渲染。
8. 最后将 attachments、subpasses 和 subpassDependencies 添加到 renderPassCreateInfo 对象中，再通过 `vkCreateRenderPass` 方法调用本地代码中的vulkan api创建render pass句柄， 并将该句柄以 long数值保存到 vkRenderPass 字段中。
### vulkanb.eng.graph.vk.FrameBuffer 类
1. FrameBuffer 代表RenderPass 需要使用的特定内存集合。RenderPass 中只包含了渲染过程的描述信息，真正的 ColorImage 或者 DepthImage 需要通过 FrameBuffer 获取。FrameBuffer 定义了哪个 ImageView 对应到 RenderPass 中的哪个 attachment。ImageView 定义了 Image 的视窗。Image 则定义了哪些内存被使用以及像素格素。
2. FrameBuffer 的构造参数主要包括 renderPass对象的句柄以及 attachments 句柄buffer(句柄数组)。这里的 attachments 句柄buffer并不是SwapChainRenderPass 中创建的 attachments。FrameBuffer 构造参数中的 attachments 句柄来自 `swapChain.getImageViews()` 方法，本质上是 包含了一个 SwapChain 的ImageView 句柄的 buffer。 然后在 FrameBuffer 的构造函数中创建 vkFrameBuffer，并设置 swapChain 的 ImageViews 和 renderPass 对象句柄。从而让 renderPass 的 attachments 和 SwapChain 的 ImageView 关联了起来
### vulkanb.eng.graph.vk.CommandBuffer 类
1. 开发者从 vulkanb.eng.graph.vk.CommandPool 中获取 commandBuffer，并将状态和绘制等操作从过 command 的形式记录在 commandBuffer 中，最后将 commandBuffer 提交给 queue 交给 GPU 执行。为了避免访问冲突，多线程下不同线程应包含不同的 commandPool。在创建 CommandPool 时需要指定队列族
2. CommandBuffer 构造方法中，第一个参数时 CommandPool、第二个参数 primary 表示该 commandBuffer 是一级命令缓冲区还是二级命令缓冲区。二级命令缓冲区中记录的命令不会提交到 queue，主要用于记录一些多个一级命令缓冲区中共享的命令。第三个参数ontTimeSubmit表示commandBuffer 中的命令是否只执行一次。本章示例中二者都被设置为了 true。最后通过`vkAllocateCommandBuffers` 在 vulkan 中创建了 commandBuffer 并拿到 commandBuffer 的句柄
3. 在 ForwardRenderActivity类中，通过代码
	```java
	commandBuffer.beginRecording();
	vkCmdBeginRenderPass(commandBuffer.getVkCommandBuffer(), renderPassBeginInfo, VK_SUBPASS_CONTENTS_INLINE);
	vkCmdEndRenderPass(commandBuffer.getVkCommandBuffer());
	commandBuffer.endRecording();
	```
	调用了 commandBuffer 的 beginRecording 和 endRecording。之间 记录了 beginRenderPass 和 endRenderPass 等命令。并在renderPassBeginInfo 中 为SwapChain中的每个 ImageView设置了相同的 clearColor

### Fence和 Semaphores
1. vulkan 中的同步是为了帮助提交 CPU 和 GPU 之间的协作效率。本章使用了 vulkan 中的信号量（semaphores）和栅栏（fence）两种同步机制。Fence 用于 CPU 和 GPU 之间的同步， 比如应用程序中通过 vkWaitForFence(fence) 等待提交的 command全部执行完毕后，直到 GPU 发出继续执行的信号。而 Semaphores 用于GPU 内部的同步
2. vulkan 中除了开发者手动设置的同步 Command以及关于 FrameBuffer 操作的Command 在 GPU 中是顺序执行的以外，其它 Command 都是无序执行的，不管是记录在同一个commandBuffer 中的 comdands 还是同一批次提交到 queue 中的 commandBuffer 中的 commands。
3. fence 只会造成当前线程等待，多线程中每个线程应该创建一个自己的 fence
### render loop
1. vulkan 中一帧图像的渲染主要包括以下几步
  1. record commands 录制命令，一般可以预先录制
  2. acquire image 获取图像，一般指从 swapChain 中获取当前渲染图像，因为 swapChain 中包含三个图像
  3. submit commands 提交commandBuffer 到 queue 中，交由 GPU 执行
  4. present image 展示图像
### SwapChain
1. 构造函数参数新增了 presentQueue和 concurrentQueues。如果 concurrentQueues 长度长度大于0且含有与 presentQueue 不同的队列族，则将vkSwapChainCreateInfo.imageSharingMode 从 VK_SHARING_MODE_EXCLUSIVE 改为 VK_SHARING_MODE_CONCURRENT。意思是会有多个不同队列族的 queue 会同时访问 SwapChain 中的 image
2. 本章中 swapChain 中的 image 共有3个。创建 ImageView 之后会同时给每个 Image 创建两个信号量对象 semaphores，其中一个信号量imgAcquisitionSemaphore用于告知图像已获取，可以提交命令进行渲染了，另一个信号量renderCompleteSemaphore用于告知该图像的渲染命令已经全部执行完，可以被呈现到屏幕上了
3. swapChain 还新增了一个成员变量currentFrame，用于记录当前被获取到的图像的 index。本章中该变量的值在0-2之间循环。示例代码中通过 `KHRSwapChain.vkAcquireNextImageKHR` 方法获取下一个可以被渲染的图像索引，该方法的第四个参数时一个信号量 对象，表示当下一个图像准备好被渲染时，该信号量对象将发出信号。 此处将当前渲染帧的imgAcquisitionSemephore 信号量传进去。
4. swapChain 新增了 presetImage 方法，用于将当前渲染的 image 呈现到屏幕上。代码核心是构建 presentInfoKHR 结构体。该结构体创建时需传入 swapChain 句柄，待呈现的 image 索引，以及等待信号量buffer(pWaitSemophores)。pWaitSemophores 传入的是 currentFrame 对应的 image 的 renderCompleteShemophore 信号量对象句柄。vulkan 会等待该renderCompleteShemophore 发出信号后再呈现渲染完毕的图像。最后再将 currentFrame 自加1。代表下一次 render 是针对后一个 image。
### vulkanb.eng.graph.vk.ForwardRenderActivity
1. 负责处理渲染前的一些任务，包括初始化 renderPass、frameBuffers、fences以及提前录制 commandBuffer（开始渲染和结束渲染的命令）
2. 在 ForwardRenderActivity.submit 方法中会调用 vkQueueSubmit 方法，该方法调用前需要构建一个VkSubmitInfo 结构体，在该结构体中需要指定当前 queue 中 commandBuffer 执行完成之后要触发的信号量。本章中传入了 currentFrame 对应的 renderCompleteSemophore。同时可以选择传入此次提交的 queue 需要等待的信号量，本章中传入了 currentFrame 对应的 imgAcquisitionSemophore。所以，每次 render循环中提交的 graphQueue 需要等待 currentFrame 对应的 image 变成 free 状态以便可以开始 render，同时queue 中的 command在执行完成后也会主动触发一个renderComplete 信号给 presentQueue，通知 presentQueue 可以开始呈现渲染完毕的 image。这就实现了同一帧中 render 和 present 的顺序执行，同时各个 image 之间的处理可以同步进行，因为Semophore 只是加在了同一帧图像处理的不同 queue 之间。
3. 同时在 Queue.submit方法中，如果传入了queue 的等待信号量的话，需要指定pWaitDstStageMask，即在waitSemophore 发出信号之前，GPU 中 pipeline 需要被阻塞的 stage。本章中因为 queue 需要等待当前帧的 image acquire 完成后将fragmentShader 生成的颜色输出到 image 中，那么dstStageMask 设置成了 `VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT`。`VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT`是渲染管线的一个阶段，在该阶段之前 fragmentShader 已经执行完，等着将生成的颜色写入 framebuffer。所以在本例中，渲染管线会执行完 fragmentShader 后才被阻塞。
4. Queue.submit 中还存在一个问题。在 SwapChainRenderPass 类中，创建 renderPass时 subpass为 image 定义了从`VK_IMAGE_LAYOUT_UNDEFINED`到`VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL` 的布局过度。这个布局转换可能发生在任意阶段，但这个转换需要图像已经获取到这个前提。所以在创建 renderPass 时我们添加了subpass dependencies，就是为了阻止 subpass 在图像获取到之前进行布局过渡。在 SwapChainRenderPass 的 subPassDependencies 创建时对
5. ForwardRenderActivity 构造函数中同时为 SwapChain 中的每帧图像都创建了一个 fence，fence 用于通知 CPU该帧图像在 GPU 中已经渲染完成，该帧图像所需的其它资源（commandBuffer、semophores等）可以被 CPU 复用。如果不设置 fence，CPU 无法知道图像是否已经渲染完毕，就即无法释放图像资源再重新创建新的，也无法复用之前的资源。不用 Semophore 是因为 Semophore 只用于 GPU 内部操作的同步？
6. ForwardRenderActivity 构造函数中为SwapChain 中的每个 image 都创建了一个 commandBuffer，这是为了提高执行效率。因为如果多个 image 共用一个 commandBuffer 的话，会因为 commandBuffer 中的 command 正在被 GPU 执行而无法在CPU 端对commandBuffer 进行操作，从而导致 CPU 干等 GPU的情况。 同时 CPU 在给 commandBuffer 录制 command 时，GPU 也会处于空闲状态。[参考链接](https://www.intel.com/content/www/us/en/developer/articles/training/practical-approach-to-vulkan-part-1.html)中分析到，准备两套 image 资源(image、fence、commandBuffer、两个 semophores 以及其它资源)是必须的，推荐是准备三套。本例中因为 SwapChain.imageViews 长度为3，所以刚好也准备了三套资源

## Chapter-06 Drawing a triangle
### vulkanb.eng.graph.vk.VulkanBuffer
1. vulkan buffer 的创建分为如下几步
  1. 构建vkBufferCreateInfo 结构体，设置 buffer 大小和usage，然后通过vkCreateBuffer 创建 vkBuffer 对象，并保存 vkBuffer 句柄。此时还没有给 buffer 分配内存。usage 属性概念和 webGPU 中的类似，sharingMode 表示该 buffer 同一时间只能被一个还是多个队列族访问
  2. 分配内存，先创建VkMemoryAllocateInfo结构体，结构体中会描述分配的内存大小以及内存的类型（能被 GPU 访问还是能被CPU 访问）。然后通过vkAllocateMemory分配实际的内存空间。并将内存空间的地址记录在 memory 字段中
  3. 最后通过vkBindBufferMemory方法将vkBuffer 和分配的 memory 关联起来
2. map memory: VulkanBuffer.mappedMemory 字段用于记录从显存映射到应用程序内存中的地址。通过vkMapMemory方法可以将内存类型中含有VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT 标志的设备内存映射到应用程序内存中，并通过 mappedMemory 记录下来，这样应用程序中就可以通过 mappedMemory 访问 buffer。同样的，在 mappedMemory 不为空的前提下也可以通过 vkUnmapMemory方法接触设备内存的内存映射。
3. VulkanBuffer 中专门添加了一个PointerBuffer pb 变量用于map时从 vulkan 获取设备内存 map 后的地址，然后赋值给 mappedMemory。所以在 cleanup 方法中需要手动释放 pb
### Vertex description
1. 抽象类 vulkanb.eng.graph.vk.VertexInputStateInfo 抽象类用于保存 VkPipelineVertexInputStateCreateInfo 结构体的句柄。该结构体用于描述各种顶点数据的格式结构等信息
2. class vulkanb.eng.graph.vk.VertexBufferStructure 继承自 VertexInputStateInfo，用于描述如何从 buffer 中提取顶点数据。在 VertexBufferStructure 构造函数中会创建 VkPipelineVertexInputStateCreateInfo 结构体，创建时需要设置 VkVertexInputAttributeDescription 结构体和VkVertexInputBindingDescription 结构体。
3. VkVertexInputBindingDescription 结构体用于描述顶点数据的存储方式。一个bindingDesc 结构体用于描述一个bindingPoint的结构。其中 binding 字段表示绑定点的索引。stride 表示一个顶点的所有数据（包括位于同一个bindPoint的所有 attributes）的字节数即每次访问一个顶点数据时的移动字节跨度。inputRate 表示每次访问顶点数据时的移动方式，VK_VERTEX_INPUT_RATE_VERTEX 表示访问一个顶点时从bindingPoint中读取一次顶点数据。VK_VERTEX_INPUT_RATE_INSTANCE 表示访问一个实例时从bindingPoint中读取一次顶点数据，这种情况用于实例渲染。
4. VkVertexInputAttributeDescription结构体用于描述某个attribute在bindingPoint中的布局情况。其中 binding字段表示 attribute 属于哪个bindingPoint，一个 vertexBuffer 中可能含有一个或者多个 bindingPoint。location 字段表示 attribute 在 vertex shader中的 location 编号，这是通过 shader 代码`layout(location = 0) in vec3 entityPos;`定义的，location  是 attribute 的唯一标识。format 字段表示 attributes 数据在 buffer 中的存储格式，本章中设置为有符号的单精度浮点数。offset 字段表示 attribute 在 buffer 中的偏移字节数，本章示例中只用到了 position 这一个 attribute，所以 offset 为0.
### Loading the data
1. 本章示例中，应用程序中的顶点数据在 Main 类中生成，并通过 vulkanb.eng.scene.ModelData.MeshData 类封装。MeshData 中包含一个三位 float 组成的 position 数组，和三位 int 组成的 indices 索引数组。生成的 ModelData 数组再经由 vulkanb.eng.graph.VulkanModel.transformModels()方法转换为VulkanBuffer并返回 vulkanb.eng.graph.VulkanModel 数组。
2. VulkanModel 类内部含有一个VulkanMesh ArrayList，VulkanMesh 封装了一个3D 模型的 position VulkanBuffer 和 indices VulkanBuffer以及索引数量。
3. 关于VulkanModel.transformModesl 方法如何将顶点数据从内存载入到显存，示例中的实现是 
  1. 在 createVerticesBuffers 方法中先创建一个临时的可以被 CPU 和 GPU 访问的 buffer作为拷贝的 source buffer，就是 TransferBuffers.srcBuffer。
  2. 同时创建一个只能被 GPU 访问的常驻 buffer 作为拷贝操作的 destination buffer，即 TransferBuffers.dstBuffer。
  3. 然后在 createVerticesBuffers 方法中执行 srcBuffer.map，将 position 数据更新到 srcBuffer 中。unmap
  4. 在recordTransferCommand方法中录制将数据从 srcBuffer 拷贝到 dstBuffer 的命令。这个 commandBuffer之后提交到 graphQueue 队列中
  5. fenceWait等待含有数据拷贝操作的 command 在 GPU 中执行完毕，commandBuffer 中同时含有 position buffer和 indices buffer的拷贝操作。
  6. fenceWait 等待之后，释放 fence、commandBuffer 以及 position 和 indices buffer的 srcBuffer。将 position buffer和 indices buffer的 dstBuffer 封装到一个 VulkanModel.VulkanMesh类中。

### Shader
1. vulkanb.eng.graph.vk.ShaderProgram类用于管理一组 shaders（vertexShader、fragmentShader 等），与 opengl 不同的是，vulkan 的 shader 代码需要自己用工具转换为 SPIR-V 字节码。开发者可以使用 glsl 或者 hlsl 编写 shader。
2. ShaderProgram中用 ShaderModule类型的数组保存各个 shader 的类型（shader stage）以及句柄
3. vulkanb.eng.graph.vk.ShaderCompiler用于将开发者编写的 shader 代码转换为SPIR-V 字节码。其中使用的时 lwjgl提供 lwjgl-shaderrc 依赖包将 .glsl 文件转换为包含 SPIR-V 字节码的 .glsl.spv 文件。

### Graphics pipeline
1. vulkan graphics pipeline 包含 input assembler, vertex shader, tesselation shader, geometry shader, rasterization, fragment shader 以及 blending 等几个stage。其中带有 shader 的几个 stage 都是可编程的。另外 vulkan pipeline是不可变的，如果在运行时需要修改 pipeline，需要提前创建多个 pipeline，然后切换 pipeline。
2. PipelineCache 通过缓存多个pipeline之间以及应用程序执行之间的公共部分来更快速地创建多个 pipeline，pipelineCache 还可以将缓存的内容保存到本地磁盘中。本例中将 vkPipelineChage 的句柄保存在了 vulkanb.eng.graph.vk.PipelineCahce.vkPipelineCache字段中。
3. 本章示例时在 ForwardRenderActivity 类的构造函数中创建的 pipeline 对象。在创建 pipeline 之前，需要先创建PipeLineCreationInfo 对象。该对象中含有 renderPass 的句柄，包含vertexShader 和 fragmentShader 的 shaderProgram 对象，颜色附件(renderTarget)的数量，以及带有Vertex Buffer存储和布局信息的 VkPipelineVertextInputStateCreateInfo 结构体句柄。
#### 创建vkPipeline 对象
> 本章的 vulkanb.eng.graph.vk.Pipeline 类的构造函数参数包括 pipelineCache 和 pipeLineCreationInfo 结构体对象。创建vulkan graph pipeline包括以下几个步骤
1. 从CreationInfo.shaderProgram 中获取 shaderModule 列表，为每个 shader module(vertexShader, fragmentShader...) 创建一个VkPipelineShaderStageCreateInfo结构体，创建时需要设置shader stage(vertext, fragment,geometry...), shader句柄， 以及通过 pName 设置shader 的 entry point 入口函数
2. 创建 VkPipelineInputAssemblyCreateInfo 结构体，其中主要是设置装配的图元基元类型，本章绘制的是三角形，故设置的是 VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
3. 创建 VkPipelineViewportStateCreateInfo 结构体，用于描述视窗个数和裁剪区域个数，二者都必须大于等于1，本章中二者都设置为1。
4. 创建 VkPipelineRasterizationStateCreateInfo 结构体，用于描述光栅化 stage 的配置。RasterizationStateCreateInfo 中可以设置 polygonMode，表示如何渲染装配后的图元，本章中设置为VK_POLYGON_MODE_FILL意思是填充三角形，设置为VK_POLYGON_MODE_LINE 时表示给三角形描边。 还可以通过cullMode 设置面剔除模式，可以剔除正面、背面、正背面或者不剔除，本章设置的是不剔除，即不管图元时正面还是背面都渲染。通过 frontFace 定义顶点顺时针排列的面是正面还是逆时针排列的面是正面。lineWidth 用于设置线段宽度
5. 创建 VkPipelineMultisampleStateCreateInfo 结构体，用于多重采样的设置。多重采样用于实现物体边缘的抗锯齿功能，原理是为光栅化后的每个图元内和图元边缘的像素内部设置多个采样点，根据图元覆盖某像素采样点的比例设置像素的颜色，从而实现模糊物体边缘的效果。rasterizationSamples用于设置多重采样的采样点个数，本章设为VK_SAMPLE_COUNT_1_BIT，即关闭了多重采样
6. 为每个renderTarget 创建一个VkPipelineColorBlendAttachmentState结构体用于设置颜色混合，颜色混合设置和 webgl 中的设置方式一致。本章中关闭了颜色混合。可以通过下面代码 
	```java
	blendAttState
		.blendEnable(true)
		.srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
		.dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
		.colorBlendOp(VK_BLEND_OP_ADD)
		.srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
		.srcAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
		.alphaBlendOp(VK_BLEND_OP_ADD)
	```
	实现alpha 混合，即`finalColor.rgb = newAlpha * newColor + (1 - newAlpha) * oldColor; finalColor.a = newAlpha.a;`。colorWriteMask 设置了哪些颜色通道可以写入 buffer
7. 创建 VkPipelineDynamicStateCreateInfo 结构体，用于设置pipeline 的动态状态。vulkan 允许我们对少量的状态在不重建 pipeline 的情况下进行动态调整。包括viewport, scissor, lineWidth 以及 blending 常量等。通过VkPipelineDynamicStateCreateInfo.pDynamicStates()方法设置动态调整的状态，添加了动态调整的状态之前的设置会被忽略，需要每次录制 command时重新设置
8. 创建 VkPipelineLayoutCreateInfo 结构体，用于定义绑定 uniform buffer等缓冲区的 binding points。本章中没有用到 uniform，但还是需要创建一个空的 VkPipelineLayout 对象
9. 通过代码
    ```java
	VkGraphicsPipelineCreateInfo.Buffer pipeline = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(shaderStages)
                    .pVertexInputState(pipeLineCreationInfo.viInputStateInfo().getVi())
                    .pInputAssemblyState(vkPipelineInputAssemblyStateCreateInfo)
                    .pViewportState(vkPipelineViewportStateCreateInfo)
                    .pRasterizationState(vkPipelineRasterizationStateCreateInfo)
                    .pMultisampleState(vkPipelineMultisampleStateCreateInfo)
                    .pColorBlendState(colorBlendState)
                    .pDynamicState(vkPipelineDynamicStateCreateInfo)
                    .layout(vkPipelineLayout)
                    .renderPass(pipeLineCreationInfo.vkRenderPass);
	```
	创建了vkPipeline 对象，除了前几步骤创建的各种结构体之外，还通过 pVertexInputState 设置了顶点着色器输入数据在 buffer 中的存储和布局等信息
#### Using the pipeline
1. ForwardRenderActivity 类的构造函数中取消了上一章中的 command 预先录制的代码，recordCommandBuffer 方法需要每帧渲染时都调用。commandBuffer 每次调用 renderCommandBuffer 时都需要重置
2. renderCommandBuffer新增代码中，第一个就是通过vkCmdBindPipeline方法将 pipeline 绑定到 commandBuffer。pipeline 中的非动态参数将会应用到该 commandBuffer 的命令录制中
3. 设置 viewport，opengl 的坐标系原点在屏幕右下角，Y 轴朝上。而Vulkan 的坐标系原点在屏幕左上角，Y 轴朝下。本章示例中先创建了 VkViewport.Buffer，设置 Y 轴坐标为屏幕高度，并设置 Y 轴高度为负的屏幕高度，相当于对 vulkan 坐标系做了一个 Y 轴翻转然后沿 Y 轴平移 height 的变换。这样 vulkan 坐标系就等价于 opengl 坐标系。最后通过vkCmdSetViewport方法将 viewport 设置添加到 commandBuffer 中。
4. submitQueue 之前的最后一步是绑定顶点 buffer。之前我们通过pipeline.pVertexInputState(pipeLineCreationInfo.viInputStateInfo().getVi()) 将带有顶点数据存储和布局信息的结构体句柄传给了 pipeline，并通过VulkanModel 类为每个 model 创建了一个存放 position 的VulkanBuffer 和存放 indices 的VulkanBuffer，并将顶点坐标和索引数据从应用程序导入到了这两个位于显存的 Buffer 中。代码中`LongBuffer vertexBuffer = stack.mallocLong(1)`的 vertexBuffer 并不是创建了真实的Buffer，只是用来临时存放 vertexBuffer 和 indicesBuffer 的 long 类型句柄。
5. **vkCmdBindVertexBuffers(cmdHandle, 0, vertexBuffer, offsets);**用于录制绑定 buffer 的命令
	1.  第二个参数 firstBinding 代表第一个bindingPoint 索引，vkCmdBindVertexBuffers 可以一次处理多个索引连续的绑定点，firstBinding 就是第一个绑定点的索引。
	2. 该方法内部调用的`nvkCmdBindVertexBuffers`方法，该方法多了一个 bindingCount 参数，绑定此次绑定的bindingPoint 数量，lwjgl 中取的是 vertexBuffer 的长度，意思 应该是buffer 的个数需要与bindingPoint 个数一致，如果多个 bindingPoint 位于了同一个 buffer 中，那么就需要传多次同一个 buffer。
	3. 第三个参数 vertexBuffer 的参数名时 pBuffers，表示传入的是多个 buffer，实际上传入的 vertexBuffer 是一个buffer 的句柄 buffer，支持传入多个 buffer 的句柄，本章示例中只传了一个 buffer 进去。如果同时绑定多个连续的 bindingPoint而且这些 bindingPoint 中存在位于同一个 buffer 的情况，那么就需要把复用的 buffer 的句柄多次写入pBuffers 中。
	4. offsets 代表绑定点在 buffer 中的字节数偏移，因为一个 buffer 中可能存在多个 bindingPoint。offsets 的长度和 bindingPoint 的长度一致
	5. 理解 nvkCmdBindVertexBuffers 方法的各个参数作用可以看下面的示例。示例中索引为0和1的两个bindingPoint 都位于 vertex_buffer 上，其中，bindingPoint0 在vertex_buffer 的偏移量为8，bindingPoint1在vertex_buffer 的偏移量为0，即 bindingPoint1在vertex_buffer 头部，bindingPoint0位于bindingPoint1后面。
	```java
	// Using the same buffer for both bindings in this example
	VkBuffer buffers[] = { vertex_buffer, vertex_buffer };
	VkDeviceSize offsets[] = { 8, 0 };

	vkCmdBindVertexBuffers(
		my_command_buffer, // commandBuffer
		0,                 // firstBinding
		2,                 // bindingCount
		buffers,           // pBuffers
		offsets,           // pOffsets
	);
	```  
## Chapter-07
1. push constant 推送常量，是GPU 提供的一种特殊的 shader 数据传输机制，可以通过`vkPhysicalDeviceProperties.limits().maxPushConstantsSize()` 获取当前显卡支持的容量上限，一般取128B。通过`vkCmdPushConstants(cmdHandle, pipeLine.getVkPipelineLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, pushConstantBuffer);`将位于 pushConstantBuffer 中的数据的更新操作写入 commandBuffer 中。其中 cmdHandle是 commandBuffer 的句柄，pipeLine.getVkPipelineLayout()返回了 pipelineLayout 对象里面含有 pushConstants 的绑定信息。VK_SHADER_STAGE_VERTEX_BIT指这个 pushConstant 只能在顶点着色器阶段被访问。0表示pushConstant 数据在 buffer 中的偏移，设置之后 shader 代码中的 push_constant 变量定义时也要设置对应的 offset。比如本例中设置 vkCmdPushConstants 的 offset 为64。那么在顶点着色器代码中也要将` mat4 projectionMatrix`改为 ` layout(offset = 64) mat4 projectionMatrix;`才行。vkCmdPushConstants 需要每次渲染时都调用。
2. push constant的布局信息在 pipeline 创建时创建并添加到 pipelineLayout 中。pipelineLayout 通过 pPushConstantRanges 设置推送常量的布局设置。`vpcr = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(128);`，VkPushConstantRange 代表一个 pushConstant 常量的布局，因为定义为 Buffer 所以可以给多个 pushConstant 常量设置布局，offset 为该常量在 pushConstant 数据中的偏移，不是 buffer 中的偏移。对于有多个 pushConstant 常量的场景下有用。本章示例中起始是传递了一个结构体类型的推送常量。stagFlags 表示推送常量可用于管线中的哪个阶段，支持用逻辑或合并多个阶段。
3. ForwardRenderActivity 类中新增了 depthAttachments 成员，长度和 swapChain.imageViews 一致（本章示例中为3）
  
## Chapter-08
### org.vulkanb.eng.graph.vk.Texture
1.  构造函数中变量 ByteBuffer buf 用于存放从纹理文件中读出的图片内容，通过 buf 创建了VulkanBuffer stgBuffer 用于将纹理图片内容传输到 GPU 单独访问的 Buffer 中，在传输完成后 stgBuffer 会被清空。所以 stgBuffer.usage 为VK_BUFFER_USAGE_TRANSFER_SRC_BIT，因为要将纹理图片内容从 buf 写入 stgBuffer，所以 stgBuffer 的 reqMask 为VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT，即buffer通过map后可以被CPU访问，并且保持CPU和GPU对该buffer操作的一致性（即CPU写入数据后，GPU可以立刻获取到最新值）。
2.  属性 image 存放具体的纹理。image.usage设为VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT，表示 image 可以作为传输的目标缓冲区以及作为采样器。因为后续需要将 stgBuffer 中的纹理图像内容传输到 image 中。
3. Texture.recordTextureTransition方法用于录制将纹理内容从 stgBuffer 传输到 image 以及转换 image 布局的命令。image对象创建时，image.layout 默认为VK_IMAGE_LAYOUT_UNDEFINED。在往 image 对象中写入图像数据前需要将 image.layout 转换成VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL。同样在写入数据之后，需要将image.layout转换成VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL，表示图像布局为着色器只读的最优布局，用于纹理采样。
4. recordTextureTransition 方法中调用recordImageTransition 方法完成image 的布局转换工作。其中用到了VkImageMemoryBarrier对象来实现 image 布局的转换
	- 内存可用性：即当对缓冲区进行写操作时，改缓冲区没有被其他操作占用或释放。
	- 内存可见性：即对于缓冲区之前的写操作对于现在的读操作是可见的。没有被其它操作干扰。
	- Barrier的作用就是指定某个缓冲区上的可用性和可见性之间的依赖关系。简单来说，就是确保缓冲区针对操作A是可用的（缓冲区的内存不受其它操作干扰），然后操作A完成之后，缓冲区的内存修改对后续操作B是可见的（操作B中访问到的缓冲区的内容只是操作A的结果）。
	- 针对VkImageMemoryBarrier而言，上一步中的操作A就是图像布局转换本身。srcAccessMask和srcStage用于指定进行布局转换前需要对图像完成的操作以及操作发生的阶段。dstAccessMask和dstStage用于指定在管线的哪个阶段对布局转换的图像执行什么操作时需要等待图像布局完成。dstAccessMask即上一步中的操作B，需要保证图像内存的可见性，即图像已经完成了布局转换。
	- 故本章的实例中，在讲stgBuffer里的图像内容拷贝到image之前，需要讲image的布局转换到适合传输的VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL布局。这一次布局转换中讲VkImageMemoryBarrier的srcStage设为了VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT，srcAccessMask设为了0，即我们不关心图像布局转换前的操作，希望布局转换越快越好。dstStage设为了VK_PIPELINE_STAGE_TRANSFER_BIT，dstAccessMask设为了VK_ACCESS_TRANSFER_WRITE_BIT，即当GPU将image作为目标执行传输操作时，需要等待图像布局转换为VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL完成。
	- 第一次纹理image布局转换完成之后，执行stgBuffer到image的拷贝。拷贝完成之后，image需要作为纹理采样器供片段着色器使用。所以需要讲image的布局由VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL转换为VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL。这一次转换中VkImageMemoryBarrier的srcStage设为VK_PIPELINE_STAGE_TRANSFER_BIT，srcAccessMask设为了VK_ACCESS_TRANSFER_WRITE_BIT，即GPU在transfer阶段对图像完成写操作后，需要阻止其它操作对image内存的访问，直到image完成布局到VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL的转换工作。dstStage设为了VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT，dstAccessMask设为了VK_ACCESS_SHADER_READ_BIT。即GPU在片段着色器阶段对image进行读操作时，需要等待image布局的完成。

### DesriptorSetLayout、DescriptorSet和DescriptorPool
1. 描述符Descriptor是一个抽象的概念，它是着色器访问buffer和image等资源的方式，类似于指针。其在代码中的具象化就是VkDescriptorBufferInfo.buffer实例所描述的buffer。
2. 描述符由描述符集DescriptorSet组织。代码中通过vkAllocateDescriptorSets方法创建描述符集实例，其中需要传入描述符池DescriptorPool的句柄和描述符集布局DescriptorSetLayout的句柄
3. 描述符池DescriptorPool负责描述符的分配和释放，应用程序可以预先分配好描述符集实例，以便在运行时快速创建描述符集实例，但预分配的描述符集的数量需要开发者自己确定
4. 描述符集布局DescriptorSetLayout是在管线创建时创建的。管线通过描述符集布局对象描述了管线的各个着色器需要多少描述符，这些描述符的类型（uniform、storage、sampler等）、描述符所在的描述符集索引(与shader代码中layout(set=x, binding=y)中的x对应)，描述符在描述符集中的绑定点binding(与shader代码中layout()的binding对应)等等。代码中通过VkDescriptorSetLayoutCreateInfo实例创建描述符集布局实例，其中pBindings方法需要传入 VkDescriptorSetLayoutBinding.Buffer 实例，即描述符集布局中绑定点的数组，在这个数组创建时需要确定每个绑定点的索引、描述符类型、描述符数量（一般为1，即一个绑定点上只有一个描述符，如果资源对象在着色器中时数组时，描述符数量不为1）以及可以访问该绑定点的着色器。
5. 创建完描述符集之后，需要通过 VkDescriptorSetLayoutBinding.Buffer对描述符集中的buffer信息进行描述， VkDescriptorSetLayoutBinding.Buffer实例是一个  VkDescriptorSetLayoutBinding数组，用于描述描述符集中需要绑定的buffer的信息，包括buffer的句柄、偏移量以及范围。 VkDescriptorSetLayoutBinding.Buffer的数量与描述符集布局中通过pBindings定义的绑定点数量一致。之后需要创建与buffer描述数组长度一致的VkWriteDescriptorSet.Buffer数组，用于更新描述符集对象。其中需要指定要更新的描述符集句柄、目标绑定点索引、描述符类型、描述符数量以及描述符缓冲区的指针。最后通过vkUpdateDescriptorSets方法批量更新描述符缓冲区到描述符集对象中。这样就通过描述符集将具体的uniform buffer绑定到了管线中的索引为A的描述符集布局的绑定点B上，这样shader中就可以通过`laytout(set = A, binding = B) uniform`来访问该uniform的buffer。代码中并没有提及描述符集布局索引A的设置，应该就是创建管线时VkPipelineLayoutCreateInfo.pSetLayouts方法DescriptorSetLayout[]参数中各个描述符集布局实例的数组索引。
6. 最后在录制渲染命令时需要录制将描述符集实例绑定到管线的绑定点上。包括pipelineBindingPoint参数，指定是渲染管线还是计算管线，layout为pipelineLayout的句柄，firstSet为第一个要绑定的描述符集的索引可以跳过前面不需要绑定的描述符集、descriptorSets为描述符集buffer的指针等参数。虽然在创建描述符集时已经传入了描述符布局，但在渲染时还是需要将描述符集显示地绑定到管线上。
### 关于多个调用多个 drawCall 时更改 uniform 的场景
1. 如果多个 mesh 共用同一个 material，比如用 Line 绘制多个车辆的轨迹，并用不同的颜色标识轨迹，颜色作为 uniform 传给 fragmentShader。在一个 commandBuffer 录制期间，需要录制多个 drawCall，每次 drawCall 前如果直接通过 buffer.map 映射的方式修改 uniform 的值，并不会达到预期的效果。因为 buffer 映射后修改这个操作时立即生效的，所以所有 drawCall都会使用到最后一个更新的 uniform 值。即使使用动态 uniform buffer也不会达到想要的效果。
2. 如果根据颜色的数量创建多个 uniform buffer，并在 drawCall 前通过 VkWriteDescriptorSet 将对应颜色的 uniform buffer绑定到描述符集上。这种方法会报错，因为在 commandBuffer 录制命令时是不能调用vkUpdateDescriptorSets 方法来绑定 buffer 到描述符上的。
3. 正确的做法应该是根据颜色的数量准备多个描述符集，每个描述符集绑定到不同颜色的 uniform buffer或者同一个 uniform buffer的不同偏移上。然后在 drawCall 前通过 vkCmdBindDescriptorSets 方法绑定对应颜色的描述符集。