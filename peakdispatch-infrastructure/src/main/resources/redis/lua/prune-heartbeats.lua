local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local cutoff = now - tonumber(ARGV[1])
local members = redis.call('ZRANGEBYSCORE', KEYS[3], '-inf', cutoff, 'LIMIT', 0, tonumber(ARGV[2]))
for _, member in ipairs(members) do
    local version = redis.call('HGET', KEYS[2], member)
    if version then
        local generation, sequence = string.match(version, '^(%d+):(%d+)$')
        if generation and sequence then
            redis.call('HSET', KEYS[2], member, generation .. ':' .. (tonumber(sequence) + 1))
        end
    end
    redis.call('ZREM', KEYS[1], member)
    redis.call('ZREM', KEYS[3], member)
end
return #members
