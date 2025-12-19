# Goal

Managing web sockets in spring boot can be annoying. 

Handling initialization, reconnection and availability and synchronization of the WebSockets can be challenging and time consuming

Plus, default WebSocket library usually allow you to configure in a vertical manner, in a centralized configuration

This library want to achieve : 
- Horizontal configuration. Declare each WebSocket as a bean like @RestController
- Annotation based approach. Handle your WebSockets with modularity and readability
- Server support. Receive external connection using @WebSocketServer
- Client support. Connect to external WebSockets using @WebSocketClient
- High Availability. Sometimes, external WebSockets close their connections after a certain time. This library can open another WebSocket just before the initial connection is connection. This will ensure a perpetual connection and no message loss.  
- Initialization support. Sometimes, when a websocket has been opened, some message must be sent to unleash the purpose of the websocket. This library can handle this via @WebSocketInitializer annotation. The websocket will not be considered available until the initializer complete.
